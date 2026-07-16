-- HAJA-25 finalize migration: 백필과 중복 정리가 끝난 뒤 최종 NOT NULL/UQ/FK를 강제한다.

begin;

select pg_advisory_xact_lock(hashtext('hajacheck:HAJA-25:schema-migration'));

do $migration$
declare
    missing_count bigint;
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
end
$migration$;

alter table inspections
    alter column assigned_inspector_id set not null;

alter table rag_documents
    alter column target_collection set not null;

alter table chat_message_citations
    alter column locator set not null,
    alter column snippet set not null;

alter table inspections
    validate constraint fk_inspections_assigned_inspector;

create unique index if not exists uq_user_plans_active_user
    on user_plans (user_id)
    where status = 'ACTIVE'::user_plan_status_type;

create unique index if not exists uq_user_plans_active_company
    on user_plans (company_id)
    where status = 'ACTIVE'::user_plan_status_type;

comment on index uq_user_plans_active_user is
    '동일 사용자에게 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';
comment on index uq_user_plans_active_company is
    '동일 회사에 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';

commit;
