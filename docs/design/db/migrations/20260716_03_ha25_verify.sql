-- HAJA-25 배포 전 읽기 전용 검증. 한 행의 ha25_schema_ready=true가 반환되어야 한다.

do $verification$
declare
    invalid_count bigint;
begin
    if to_regclass('public.company_memberships') is null then
        raise exception 'company_memberships table is missing';
    end if;

    if (select array_agg(e.enumlabel::text order by e.enumsortorder)
        from pg_enum e where e.enumtypid = 'company_membership_status_type'::regtype)
       <> array['PENDING', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED'] then
        raise exception 'company_membership_status_type labels do not match the canonical DDL';
    end if;

    if (select array_agg(e.enumlabel::text order by e.enumsortorder)
        from pg_enum e where e.enumtypid = 'rag_target_collection_type'::regtype)
       <> array['REGULATIONS', 'DEFECT_KB'] then
        raise exception 'rag_target_collection_type labels do not match the canonical DDL';
    end if;

    if (select array_agg(e.enumlabel::text order by e.enumsortorder)
        from pg_enum e where e.enumtypid = 'rag_doc_verification_status_type'::regtype)
       <> array['UNVERIFIED', 'VERIFIED'] then
        raise exception 'rag_doc_verification_status_type labels do not match the canonical DDL';
    end if;

    if not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'inspections'
          and column_name = 'assigned_inspector_id' and is_nullable = 'NO'
    ) then
        raise exception 'inspections.assigned_inspector_id is missing or nullable';
    end if;

    if not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'rag_documents'
          and column_name = 'target_collection' and is_nullable = 'NO'
    ) then
        raise exception 'rag_documents.target_collection is missing or nullable';
    end if;

    if not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'chat_message_citations'
          and column_name = 'locator' and is_nullable = 'NO'
    ) then
        raise exception 'chat_message_citations.locator is missing or nullable';
    end if;

    select count(*) into invalid_count
    from chat_message_citations where snippet is null;
    if invalid_count > 0 then
        raise exception '% citations still have a null snippet', invalid_count;
    end if;

    if to_regclass('public.uq_user_plans_active_user') is null
       or to_regclass('public.uq_user_plans_active_company') is null then
        raise exception 'ACTIVE user plan uniqueness indexes are missing';
    end if;

    if not exists (
        select 1 from pg_constraint
        where conname = 'fk_inspections_assigned_inspector'
          and conrelid = 'inspections'::regclass
          and convalidated
    ) then
        raise exception 'assigned inspector foreign key is missing or not validated';
    end if;

    if not exists (
        select 1 from pg_trigger
        where tgname = 'trg_company_memberships_set_updated_at' and not tgisinternal
    ) then
        raise exception 'company_memberships updated_at trigger is missing';
    end if;
end
$verification$;

select true as ha25_schema_ready;
