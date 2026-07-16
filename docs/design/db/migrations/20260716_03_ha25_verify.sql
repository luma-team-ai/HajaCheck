-- HAJA-25 배포 전 읽기 전용 검증. 한 행의 ha25_schema_ready=true가 반환되어야 한다.

do $verification$
declare
    invalid_count bigint;
    invalid_columns text;
    invalid_indexes text;
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
        raise exception 'v0.3 UNIQUE user_consents(user_id, policy_type, policy_version) is missing';
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
        raise exception 'v0.3 UNIQUE inspections(facility_id, round_no) is missing';
    end if;

    if not exists (
        select 1
        from pg_constraint c
        where c.conrelid = 'company_memberships'::regclass
          and c.contype = 'u'
          and (
              select array_agg(a.attname::text order by k.ordinality)
              from unnest(c.conkey) with ordinality as k(attnum, ordinality)
              join pg_attribute a
                on a.attrelid = c.conrelid
               and a.attnum = k.attnum
          ) = array['company_id', 'user_id']
    ) then
        raise exception 'UNIQUE company_memberships(company_id, user_id) is missing';
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
        raise exception 'v0.3 media.mime_signature_verified DEFAULT false NOT NULL is missing';
    end if;

    select string_agg(expected.table_name || '.lock_version', ', ' order by expected.table_name)
    into invalid_columns
    from (values
        ('companies'),
        ('company_memberships'),
        ('defects'),
        ('reports'),
        ('counsel_tickets'),
        ('rag_documents')
    ) as expected(table_name)
    left join information_schema.columns actual
      on actual.table_schema = 'public'
     and actual.table_name = expected.table_name
     and actual.column_name = 'lock_version'
     and actual.is_nullable = 'NO'
    where actual.column_name is null;

    if invalid_columns is not null then
        raise exception 'optimistic-lock columns are missing or nullable: %', invalid_columns;
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

    select string_agg(expected.index_name, ', ' order by expected.index_name)
    into invalid_indexes
    from unnest(array[
        'idx_company_memberships_company_status',
        'idx_company_memberships_user_status',
        'uq_company_memberships_approved_user',
        'idx_inspections_assigned_inspector',
        'idx_rag_documents_embedding_status',
        'idx_rag_documents_target_collection',
        'uq_user_plans_active_user',
        'uq_user_plans_active_company'
    ]) as expected(index_name)
    left join pg_class index_class
      on index_class.relname = expected.index_name
     and index_class.relnamespace = 'public'::regnamespace
    left join pg_index index_meta on index_meta.indexrelid = index_class.oid
    where index_class.oid is null
       or not coalesce(index_meta.indisvalid, false)
       or not coalesce(index_meta.indisready, false);

    if invalid_indexes is not null then
        raise exception 'required indexes are missing or invalid: %', invalid_indexes;
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
        raise exception 'an APPROVED+VERIFIED company lacks a valid owner membership or matching users.company_id';
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
