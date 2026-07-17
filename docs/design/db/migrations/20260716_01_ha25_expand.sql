-- HAJA-25 expand migration: 기존 v0.3 계열 DB에 신규 구조를 nullable/비차단 형태로 추가한다.
-- 이 단계 뒤 README의 데이터 백필을 완료하고 finalize 스크립트를 실행해야 한다.

-- Run this file with autocommit enabled. The indexes below must be created
-- concurrently, which PostgreSQL does not allow inside a transaction.
select pg_advisory_lock(hashtext('hajacheck:HAJA-25:schema-migration'));

do $migration$
begin
    if to_regclass('public.users') is null
       or to_regclass('public.companies') is null
       or to_regclass('public.user_consents') is null
       or to_regclass('public.inspections') is null
       or to_regclass('public.media') is null
       or to_regclass('public.rag_documents') is null
       or to_regclass('public.chat_message_citations') is null then
        raise exception 'HAJA-25 migration requires the v0.3 baseline tables';
    end if;
end
$migration$;

-- HAJA-25가 새로 만드는 제약으로 오인되지 않도록 v0.3 기준선의 핵심 UQ/NOT NULL을 구조로 검증한다.
-- 제약명은 환경마다 달라질 수 있으므로 이름이 아니라 제약 컬럼 순서로 확인한다.
do $migration$
begin
    if not exists (
        select 1
        from pg_constraint c
        where c.conrelid = 'user_consents'::regclass
          and c.contype = 'u'
          and (
              select array_agg(a.attname::text order by k.ordinality)
              from unnest(c.conkey) with ordinality as k(attnum, ordinality)
              join pg_attribute a
                on a.attrelid = c.conrelid
               and a.attnum = k.attnum
          ) = array['user_id', 'policy_type', 'policy_version']
    ) then
        raise exception 'HAJA-25 migration requires v0.3 UNIQUE user_consents(user_id, policy_type, policy_version)';
    end if;

    if not exists (
        select 1
        from pg_constraint c
        where c.conrelid = 'inspections'::regclass
          and c.contype = 'u'
          and (
              select array_agg(a.attname::text order by k.ordinality)
              from unnest(c.conkey) with ordinality as k(attnum, ordinality)
              join pg_attribute a
                on a.attrelid = c.conrelid
               and a.attnum = k.attnum
          ) = array['facility_id', 'round_no']
    ) then
        raise exception 'HAJA-25 migration requires v0.3 UNIQUE inspections(facility_id, round_no)';
    end if;

    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'media'
          and column_name = 'mime_signature_verified'
          and is_nullable = 'NO'
          and lower(column_default) like 'false%'
    ) then
        raise exception 'HAJA-25 migration requires v0.3 media.mime_signature_verified DEFAULT false NOT NULL';
    end if;
end
$migration$;

do $migration$
begin
    if to_regtype('public.company_membership_status_type') is null then
        create type company_membership_status_type as enum
            ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED');
    end if;
    if to_regtype('public.rag_target_collection_type') is null then
        create type rag_target_collection_type as enum ('REGULATIONS', 'DEFECT_KB');
    end if;
    if to_regtype('public.rag_doc_verification_status_type') is null then
        create type rag_doc_verification_status_type as enum ('UNVERIFIED', 'VERIFIED');
    end if;
end
$migration$;

create table if not exists company_memberships
(
    id          bigint generated always as identity primary key,
    lock_version bigint default 0 not null,
    company_id  bigint not null references companies,
    user_id     bigint not null references users,
    invited_by  bigint references users,
    status      company_membership_status_type
        default 'PENDING'::company_membership_status_type not null,
    approved_at timestamp with time zone,
    expires_at  timestamp with time zone,
    revoked_at  timestamp with time zone,
    created_at  timestamp with time zone default now() not null,
    updated_at  timestamp with time zone default now() not null,
    constraint company_memberships_company_id_user_id_key unique (company_id, user_id),
    constraint ck_company_memberships_approved_at
        check (status <> 'APPROVED'::company_membership_status_type or approved_at is not null),
    constraint ck_company_memberships_revoked_at
        check (status <> 'REVOKED'::company_membership_status_type or revoked_at is not null),
    constraint ck_company_memberships_expiry
        check (expires_at is null or
               (expires_at > created_at and (approved_at is null or expires_at > approved_at)))
);

-- 이전 버전의 expand를 이미 실행한 환경에서도 재실행만으로 낙관적 락 컬럼을 보강한다.
-- finalize 전까지는 nullable로 추가해 긴 테이블 검증 잠금을 피하고, 상수 기본값으로 기존 행을 0으로 읽는다.
alter table companies
    add column if not exists lock_version bigint default 0;
alter table company_memberships
    add column if not exists lock_version bigint default 0;
alter table defects
    add column if not exists lock_version bigint default 0;
alter table reports
    add column if not exists lock_version bigint default 0;
alter table counsel_tickets
    add column if not exists lock_version bigint default 0;
alter table rag_documents
    add column if not exists lock_version bigint default 0;
-- notifications는 상태 머신은 아니나(단순 읽음 플래그), 다른 가변 Entity와의 낙관적 락 일관성을 위해 포함한다.
alter table notifications
    add column if not exists lock_version bigint default 0;

create index if not exists idx_company_memberships_company_status
    on company_memberships (company_id, status);
create index if not exists idx_company_memberships_user_status
    on company_memberships (user_id, status);
create unique index if not exists uq_company_memberships_approved_user
    on company_memberships (user_id)
    where status = 'APPROVED'::company_membership_status_type;

-- 승인·검증된 기존 회사의 owner_user_id는 오너 소속의 신뢰 가능한 근거다.
-- 동일 사용자가 복수 회사를 소유하거나 다른 회사 포인터를 가진 경우에는 자동 덮어쓰지 않는다.
do $migration$
begin
    if exists (
        select c.owner_user_id
        from companies c
        where c.status = 'APPROVED'::company_status_type
          and c.verification_status = 'VERIFIED'::business_verification_status_type
        group by c.owner_user_id
        having count(*) > 1
    ) then
        raise exception 'HAJA-25 expand blocked: one user owns multiple APPROVED+VERIFIED companies';
    end if;

    if exists (
        select 1
        from companies c
        join users u on u.id = c.owner_user_id
        where c.status = 'APPROVED'::company_status_type
          and c.verification_status = 'VERIFIED'::business_verification_status_type
          and u.company_id is not null
          and u.company_id <> c.id
    ) then
        raise exception 'HAJA-25 expand blocked: an approved company owner points to another company';
    end if;
end
$migration$;

insert into company_memberships (company_id, user_id, status, approved_at)
select c.id,
       c.owner_user_id,
       case
           when c.status = 'APPROVED'::company_status_type
            and c.verification_status = 'VERIFIED'::business_verification_status_type
               then 'APPROVED'::company_membership_status_type
           else 'PENDING'::company_membership_status_type
       end,
       case
           when c.status = 'APPROVED'::company_status_type
            and c.verification_status = 'VERIFIED'::business_verification_status_type
               then coalesce(c.reviewed_at, c.verified_at, c.created_at, now())
           else null
       end
from companies c
on conflict (company_id, user_id) do update
set status = excluded.status,
    approved_at = excluded.approved_at,
    expires_at = null,
    revoked_at = null,
    updated_at = now()
where company_memberships.status = 'PENDING'::company_membership_status_type
  and excluded.status = 'APPROVED'::company_membership_status_type;

update users u
set company_id = c.id,
    updated_at = now()
from companies c
where u.id = c.owner_user_id
  and c.status = 'APPROVED'::company_status_type
  and c.verification_status = 'VERIFIED'::business_verification_status_type
  and u.company_id is distinct from c.id;

-- 비오너의 기존 company_id는 승인 감사 근거가 아니므로 먼저 PENDING 후보로 격리한다.
insert into company_memberships (company_id, user_id, status)
select u.company_id, u.id, 'PENDING'::company_membership_status_type
from users u
where u.company_id is not null
on conflict (company_id, user_id) do nothing;

-- 레거시 비오너를 자동 승인하면 초대 승인 감사를 우회하고, 자동 NULL 처리하면 정상 구성원의
-- 접근권을 조용히 회수한다. 둘 다 하지 않고 운영자가 README 절차로 각 후보를 명시적으로
-- APPROVED 백필하거나 company_id를 제거할 때까지 expand를 중단한다.
do $migration$
begin
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
                and cm.approved_at is not null
                and cm.revoked_at is null
                and (cm.expires_at is null or cm.expires_at > now())
          )
    ) then
        raise exception 'HAJA-25 expand blocked: legacy company members require explicit APPROVED backfill or company_id removal (see migrations/README.md)';
    end if;
end
$migration$;

alter table inspections
    add column if not exists assigned_inspector_id bigint;

do $migration$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'fk_inspections_assigned_inspector'
          and conrelid = 'inspections'::regclass
    ) then
        alter table inspections
            add constraint fk_inspections_assigned_inspector
            foreign key (assigned_inspector_id) references users(id) not valid;
    end if;
end
$migration$;

create index concurrently if not exists idx_inspections_assigned_inspector
    on inspections (assigned_inspector_id);

alter table rag_documents
    add column if not exists target_collection rag_target_collection_type,
    add column if not exists effective_date date,
    add column if not exists publisher varchar(200),
    add column if not exists authored_at date,
    add column if not exists verification_status rag_doc_verification_status_type;

create index concurrently if not exists idx_rag_documents_embedding_status
    on rag_documents (embedding_status);

create index concurrently if not exists idx_rag_documents_target_collection
    on rag_documents (target_collection);

alter table chat_message_citations
    add column if not exists locator text;

-- v0.3에서는 snippet이 nullable이므로 finalize 전 원문을 복구할 수 있게 유지한다.

do $migration$
begin
    if to_regprocedure('public.set_updated_at()') is null then
        raise exception 'set_updated_at() from the v0.3 baseline is required';
    end if;

    if not exists (select 1 from pg_trigger where tgname = 'trg_users_set_updated_at'
                   and tgrelid = 'users'::regclass and not tgisinternal) then
        create trigger trg_users_set_updated_at before update on users
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_companies_set_updated_at'
                   and tgrelid = 'companies'::regclass and not tgisinternal) then
        create trigger trg_companies_set_updated_at before update on companies
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_company_memberships_set_updated_at'
                   and tgrelid = 'company_memberships'::regclass and not tgisinternal) then
        create trigger trg_company_memberships_set_updated_at before update on company_memberships
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_facilities_set_updated_at'
                   and tgrelid = 'facilities'::regclass and not tgisinternal) then
        create trigger trg_facilities_set_updated_at before update on facilities
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_plans_set_updated_at'
                   and tgrelid = 'plans'::regclass and not tgisinternal) then
        create trigger trg_plans_set_updated_at before update on plans
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_reports_set_updated_at'
                   and tgrelid = 'reports'::regclass and not tgisinternal) then
        create trigger trg_reports_set_updated_at before update on reports
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_bot_scenarios_set_updated_at'
                   and tgrelid = 'bot_scenarios'::regclass and not tgisinternal) then
        create trigger trg_bot_scenarios_set_updated_at before update on bot_scenarios
            for each row execute procedure set_updated_at();
    end if;
end
$migration$;

comment on trigger trg_users_set_updated_at on users is 'users 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_companies_set_updated_at on companies is 'companies 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_company_memberships_set_updated_at on company_memberships is 'company_memberships 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_plans_set_updated_at on plans is 'plans 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_facilities_set_updated_at on facilities is 'facilities 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_reports_set_updated_at on reports is 'reports 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_bot_scenarios_set_updated_at on bot_scenarios is 'bot_scenarios 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

comment on column users.company_id is '현재 소속 기업의 조회 편의 포인터. 개인 사용자는 NULL이며 company_memberships의 유효한 승인 행과 일치해야 하지만 단독 권한 근거로 사용하지 않는다.';

comment on table company_memberships is '기업 초대·승인·회수·만료 이력과 현재 소속 판정의 기준을 관리한다.';
comment on column companies.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';
comment on column company_memberships.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';
comment on column defects.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';
comment on column reports.lock_version is '보고서 업무 버전과 별개인 상태 전이 낙관적 락 버전';
comment on column counsel_tickets.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';
comment on column rag_documents.lock_version is '임베딩·검증 상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';
comment on column notifications.lock_version is '동시 갱신 충돌 감지용 낙관적 락 버전(상태 머신은 아니나 다른 가변 Entity와의 일관성을 위해 적용)';
comment on column company_memberships.company_id is '소속 회사 식별자';
comment on column company_memberships.user_id is '소속 사용자 식별자';
comment on column company_memberships.invited_by is '초대한 회사 오너 또는 관리자 사용자 식별자. 오너의 최초 멤버십은 NULL 가능';
comment on column company_memberships.status is '기업 멤버십 상태';
comment on column company_memberships.approved_at is '멤버십 승인 시각';
comment on column company_memberships.expires_at is '멤버십 만료 시각. NULL이면 명시적 만료 없음';
comment on column company_memberships.revoked_at is '멤버십 회수 시각';
comment on column company_memberships.created_at is '멤버십 생성 시각';
comment on column company_memberships.updated_at is '멤버십 최종 상태 변경 시각';

comment on column user_consents.user_id is '동의한 사용자 식별자. 감사·분쟁 대응을 위해 동의 이력은 사용자 삭제와 독립적으로 보존해야 하나, 실측 결과 FK는 ON DELETE CASCADE이므로 보존은 DB 제약이 아닌 운영 원칙(사용자 탈퇴는 soft delete)으로 보장한다.';
comment on table user_plans is '개인(user_id) 또는 회사(company_id)에 적용된 구독 요금제와 이용 기간을 관리한다. 회사 귀속 행은 유효한 승인 company_memberships 사용자에게만 상속된다.';
comment on column inspections.assigned_inspector_id is '점검 담당자로 배정된 점검자 사용자 식별자. 본 증분 스크립트는 기존 값을 자동 추정·백필하지 않는다 — finalize 전 담당자 확정값으로 먼저 백필해야 하며, 근거 없이 created_by를 자동 복사하지 않는다(백필 절차는 migrations/README.md 참조)';
comment on column media.source_video_id is '프레임 이미지의 원본 영상 식별자(media.id 자기 참조 개념이나 FK 미설정 — 영상 프레임 추출 파이프라인의 유연한 기록을 위함)';
comment on column rag_documents.target_collection is '이 문서의 청크가 임베딩되는 Chroma 컬렉션(regulations/defect_kb)';
comment on column rag_documents.effective_date is '문서 시행일(법규 개정 추적용, LAW 문서만 채움 — GUIDELINE/DEFECT_KB 문서는 NULL 허용)';
comment on column rag_documents.publisher is '발행 기관/부처명(법규·지침 문서 출처 표시용, regulations 대상 — 해당 없는 문서는 NULL)';
comment on column rag_documents.authored_at is '문서 작성일(주로 하자 지식 문서 대상 — effective_date와 별개 개념)';
comment on column rag_documents.verification_status is '문서 검증 여부(주로 defect_kb 하자 지식 문서의 전문가 검토 통과 여부 — regulations는 NULL 허용)';
comment on column chat_message_citations.locator is '화면 표시용 출처 라벨(예: 제12조, 제12조 ①, 12페이지)';

select pg_advisory_unlock(hashtext('hajacheck:HAJA-25:schema-migration'));
