-- HAJA-25 finalize migration: 백필과 중복 정리가 끝난 뒤 최종 NOT NULL/UQ/FK를 강제한다.

-- Run this file with autocommit enabled. CREATE INDEX CONCURRENTLY cannot run
-- inside a transaction. Validated CHECK constraints make SET NOT NULL metadata-only.
select pg_advisory_lock(hashtext('hajacheck:HAJA-25:schema-migration'));

do $migration$
declare
    missing_count bigint;
    pending_membership_count bigint;
begin
    select count(*) into missing_count
    from inspections where assigned_inspector_id is null;
    if missing_count > 0 then
        raise exception 'HAJA-25 finalize blocked: % inspections need assigned_inspector_id backfill',
            missing_count;
    end if;

    select count(*) into missing_count
    from rag_documents where target_collection is null;
    if missing_count > 0 then
        raise exception 'HAJA-25 finalize blocked: % rag_documents need target_collection backfill',
            missing_count;
    end if;

    select count(*) into missing_count
    from chat_message_citations where locator is null or snippet is null;
    if missing_count > 0 then
        raise exception 'HAJA-25 finalize blocked: % citations need locator/snippet backfill',
            missing_count;
    end if;

    select sum(null_count) into missing_count
    from (
        select count(*) as null_count from companies where lock_version is null
        union all
        select count(*) from company_memberships where lock_version is null
        union all
        select count(*) from defects where lock_version is null
        union all
        select count(*) from reports where lock_version is null
        union all
        select count(*) from counsel_tickets where lock_version is null
        union all
        select count(*) from rag_documents where lock_version is null
        union all
        select count(*) from notifications where lock_version is null
    ) lock_versions;
    if missing_count > 0 then
        raise exception 'HAJA-25 finalize blocked: % state-machine rows need lock_version backfill',
            missing_count;
    end if;

    if exists (
        select 1 from user_plans
        where status = 'ACTIVE'::user_plan_status_type and user_id is not null
        group by user_id having count(*) > 1
    ) then
        raise exception 'HAJA-25 finalize blocked: duplicate ACTIVE user plans exist';
    end if;

    if exists (
        select 1 from user_plans
        where status = 'ACTIVE'::user_plan_status_type and company_id is not null
        group by company_id having count(*) > 1
    ) then
        raise exception 'HAJA-25 finalize blocked: duplicate ACTIVE company plans exist';
    end if;

    if exists (
        select 1 from counsel_tickets
        where session_id is not null
        group by session_id having count(*) > 1
    ) then
        raise exception 'HAJA-25 finalize blocked: one counsel session is assigned to multiple tickets';
    end if;

    if exists (
        select 1
        from companies c
        join users u on u.id = c.owner_user_id
        left join company_memberships cm
          on cm.company_id = c.id
         and cm.user_id = c.owner_user_id
         and cm.status = 'APPROVED'::company_membership_status_type
         and cm.approved_at is not null
         and cm.revoked_at is null
         and (cm.expires_at is null or cm.expires_at > now())
        where c.status = 'APPROVED'::company_status_type
          and c.verification_status = 'VERIFIED'::business_verification_status_type
          and (cm.id is null or u.company_id is distinct from c.id)
    ) then
        raise exception 'HAJA-25 finalize blocked: an APPROVED+VERIFIED company lacks a valid owner membership or matching users.company_id';
    end if;

    select count(*) into pending_membership_count
    from company_memberships
    where status = 'PENDING'::company_membership_status_type;
    if pending_membership_count > 0 then
        raise warning 'HAJA-25 finalize: % PENDING company memberships remain quarantined and receive no company entitlement',
            pending_membership_count;
    end if;

    -- expand가 격리 대상 사용자의 company_id를 null로 정리하지만, expand와 finalize 사이에
    -- 애플리케이션 쓰기가 완전히 멈추지 않으면 그 사이 새로 생긴 stale company_id가 남을 수 있다.
    -- users.company_id를 인가 근거로 쓰는 기존 경로(AuthService.validateAssignableInspector 등)를
    -- 보호하기 위해, 유효한 APPROVED 멤버십이 없는 company_id 잔존을 최종 확정 전에 차단한다.
    if exists (
        select 1
        from users u
        where u.company_id is not null
          and not exists (
              select 1
              from company_memberships cm
              where cm.company_id = u.company_id
                and cm.user_id = u.id
                and cm.status = 'APPROVED'::company_membership_status_type
                and cm.revoked_at is null
                and (cm.expires_at is null or cm.expires_at > now())
          )
    ) then
        raise exception 'HAJA-25 finalize blocked: users.company_id set without a matching valid APPROVED membership remain (re-run the expand cleanup)';
    end if;
end
$migration$;

do $migration$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_inspections_assigned_inspector_not_null'
                   and conrelid = 'inspections'::regclass) then
        alter table inspections add constraint ck_inspections_assigned_inspector_not_null
            check (assigned_inspector_id is not null) not valid;
    end if;
end
$migration$;
alter table inspections validate constraint ck_inspections_assigned_inspector_not_null;
alter table inspections alter column assigned_inspector_id set not null;
alter table inspections drop constraint ck_inspections_assigned_inspector_not_null;

-- assigned_inspector_id의 FK는 users(id)만 참조할 뿐 회사 경계는 강제하지 않는다.
-- AuthService.validateAssignableInspector가 매 생성 시점에 요청자(=created_by)와 배정자가
-- 같은 회사(users.company_id)인지 이미 검증하지만, 이는 애플리케이션 계층 방어일 뿐이다.
-- 백필이 끝난 뒤(NOT NULL 확정 후)에만 설치해, 정리되지 않은 과거 데이터가 백필 자체를 막지 않게 한다.
create or replace function check_inspection_assigned_inspector_company() returns trigger as $check$
declare
    creator_company_id bigint;
    inspector_company_id bigint;
begin
    select company_id into creator_company_id from users where id = new.created_by;
    select company_id into inspector_company_id from users where id = new.assigned_inspector_id;
    -- AuthService.validateAssignableInspector와 동일하게 양쪽 모두 회사 소속이고 회사가 같을 때만 허용한다.
    -- 따라서 한쪽 또는 양쪽 company_id가 NULL인 무소속 사용자 배정도 DB 경계에서 차단한다.
    if creator_company_id is null
        or inspector_company_id is null
        or creator_company_id is distinct from inspector_company_id then
        raise exception
            'assigned_inspector_id % must belong to the same company as created_by %',
            new.assigned_inspector_id, new.created_by;
    end if;
    return new;
end;
$check$ language plpgsql;

create or replace trigger trg_inspections_check_assigned_inspector_company
    before insert or update of assigned_inspector_id, created_by on inspections
    for each row execute procedure check_inspection_assigned_inspector_company();

comment on function check_inspection_assigned_inspector_company() is
    'inspections.assigned_inspector_id와 created_by가 모두 회사 소속이고 users.company_id 기준으로 같은 회사인지 강제한다(무소속은 거부 — HAJA-25 P2 DB 레벨 방어).';

do $migration$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_rag_documents_target_collection_not_null'
                   and conrelid = 'rag_documents'::regclass) then
        alter table rag_documents add constraint ck_rag_documents_target_collection_not_null
            check (target_collection is not null) not valid;
    end if;
end
$migration$;
alter table rag_documents validate constraint ck_rag_documents_target_collection_not_null;
alter table rag_documents alter column target_collection set not null;
alter table rag_documents drop constraint ck_rag_documents_target_collection_not_null;

do $migration$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_chat_message_citations_locator_snippet_not_null'
                   and conrelid = 'chat_message_citations'::regclass) then
        alter table chat_message_citations add constraint ck_chat_message_citations_locator_snippet_not_null
            check (locator is not null and snippet is not null) not valid;
    end if;
end
$migration$;
alter table chat_message_citations validate constraint ck_chat_message_citations_locator_snippet_not_null;
alter table chat_message_citations
    alter column locator set not null,
    alter column snippet set not null;
alter table chat_message_citations drop constraint ck_chat_message_citations_locator_snippet_not_null;

do $migration$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_companies_lock_version_not_null'
                   and conrelid = 'companies'::regclass) then
        alter table companies add constraint ck_companies_lock_version_not_null
            check (lock_version is not null) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_company_memberships_lock_version_not_null'
                   and conrelid = 'company_memberships'::regclass) then
        alter table company_memberships add constraint ck_company_memberships_lock_version_not_null
            check (lock_version is not null) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_defects_lock_version_not_null'
                   and conrelid = 'defects'::regclass) then
        alter table defects add constraint ck_defects_lock_version_not_null
            check (lock_version is not null) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_reports_lock_version_not_null'
                   and conrelid = 'reports'::regclass) then
        alter table reports add constraint ck_reports_lock_version_not_null
            check (lock_version is not null) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_counsel_tickets_lock_version_not_null'
                   and conrelid = 'counsel_tickets'::regclass) then
        alter table counsel_tickets add constraint ck_counsel_tickets_lock_version_not_null
            check (lock_version is not null) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_rag_documents_lock_version_not_null'
                   and conrelid = 'rag_documents'::regclass) then
        alter table rag_documents add constraint ck_rag_documents_lock_version_not_null
            check (lock_version is not null) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_notifications_lock_version_not_null'
                   and conrelid = 'notifications'::regclass) then
        alter table notifications add constraint ck_notifications_lock_version_not_null
            check (lock_version is not null) not valid;
    end if;
end
$migration$;

alter table companies validate constraint ck_companies_lock_version_not_null;
alter table companies alter column lock_version set not null;
alter table companies drop constraint ck_companies_lock_version_not_null;

alter table company_memberships validate constraint ck_company_memberships_lock_version_not_null;
alter table company_memberships alter column lock_version set not null;
alter table company_memberships drop constraint ck_company_memberships_lock_version_not_null;

alter table defects validate constraint ck_defects_lock_version_not_null;
alter table defects alter column lock_version set not null;
alter table defects drop constraint ck_defects_lock_version_not_null;

alter table reports validate constraint ck_reports_lock_version_not_null;
alter table reports alter column lock_version set not null;
alter table reports drop constraint ck_reports_lock_version_not_null;

alter table counsel_tickets validate constraint ck_counsel_tickets_lock_version_not_null;
alter table counsel_tickets alter column lock_version set not null;
alter table counsel_tickets drop constraint ck_counsel_tickets_lock_version_not_null;

alter table rag_documents validate constraint ck_rag_documents_lock_version_not_null;
alter table rag_documents alter column lock_version set not null;
alter table rag_documents drop constraint ck_rag_documents_lock_version_not_null;

alter table notifications validate constraint ck_notifications_lock_version_not_null;
alter table notifications alter column lock_version set not null;
alter table notifications drop constraint ck_notifications_lock_version_not_null;

alter table inspections
    validate constraint fk_inspections_assigned_inspector;

create unique index concurrently if not exists uq_user_plans_active_user
    on user_plans (user_id)
    where status = 'ACTIVE'::user_plan_status_type;

create unique index concurrently if not exists uq_user_plans_active_company
    on user_plans (company_id)
    where status = 'ACTIVE'::user_plan_status_type;

create unique index concurrently if not exists uq_counsel_tickets_session
    on counsel_tickets (session_id)
    where session_id is not null;

comment on index uq_user_plans_active_user is
    '동일 사용자에게 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';
comment on index uq_user_plans_active_company is
    '동일 회사에 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';
comment on index uq_counsel_tickets_session is
    '하나의 전문상담 세션이 여러 상담 티켓에 중복 배정되는 것을 방지한다.';

select pg_advisory_unlock(hashtext('hajacheck:HAJA-25:schema-migration'));
