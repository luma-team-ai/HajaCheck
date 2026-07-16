-- HAJA-25 배포 전 읽기 전용 검증. 한 행의 ha25_schema_ready=true가 반환되어야 한다.

do $verification$
declare
    invalid_count bigint;
    invalid_columns text;
    invalid_indexes text;
    invalid_triggers text;
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

    if exists (
        select 1
        from user_consents
        group by user_id, policy_type, policy_version
        having count(*) > 1
    ) then
        raise exception 'duplicate user_consents(user_id, policy_type, policy_version) rows remain';
    end if;

    if exists (
        select 1
        from inspections
        group by facility_id, round_no
        having count(*) > 1
    ) then
        raise exception 'duplicate inspections(facility_id, round_no) rows remain';
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

    -- 컬럼 존재/타입을 먼저 확인한 뒤 실제 행의 NULL 잔존 여부와 최종 DEFAULT/NOT NULL을 각각 검증한다.
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
     and actual.data_type = 'bigint'
    where actual.column_name is null;

    if invalid_columns is not null then
        raise exception 'optimistic-lock bigint columns are missing: %', invalid_columns;
    end if;

    if exists (
        select 1
        from (
            select lock_version from companies
            union all select lock_version from company_memberships
            union all select lock_version from defects
            union all select lock_version from reports
            union all select lock_version from counsel_tickets
            union all select lock_version from rag_documents
        ) state_machine_rows
        where lock_version is null
    ) then
        raise exception 'state-machine rows with null lock_version remain';
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
     and regexp_replace(lower(coalesce(actual.column_default, '')), '[[:space:]()]', '', 'g')
         in ('0', '0::bigint', '0::int8')
    where actual.column_name is null;

    if invalid_columns is not null then
        raise exception 'optimistic-lock columns must be DEFAULT 0 NOT NULL: %', invalid_columns;
    end if;

    if not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'inspections'
          and column_name = 'assigned_inspector_id'
          and data_type = 'bigint' and is_nullable = 'NO'
    ) then
        raise exception 'inspections.assigned_inspector_id is missing or nullable';
    end if;

    if not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'rag_documents'
          and column_name = 'target_collection'
          and udt_schema = 'public' and udt_name = 'rag_target_collection_type'
          and is_nullable = 'NO'
    ) then
        raise exception 'rag_documents.target_collection is missing or nullable';
    end if;

    if not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'chat_message_citations'
          and column_name = 'locator' and data_type = 'text' and is_nullable = 'NO'
    ) then
        raise exception 'chat_message_citations.locator is missing or nullable';
    end if;

    if not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'chat_message_citations'
          and column_name = 'snippet' and data_type = 'text' and is_nullable = 'NO'
    ) then
        raise exception 'chat_message_citations.snippet is missing or nullable';
    end if;

    select count(*) into invalid_count
    from chat_message_citations where snippet is null;
    if invalid_count > 0 then
        raise exception '% citations still have a null snippet', invalid_count;
    end if;

    select string_agg(expected.index_name, ', ' order by expected.index_name)
    into invalid_indexes
    from (values
        ('idx_company_memberships_company_status', 'company_memberships', false,
            array['company_id', 'status']::text[], null::text),
        ('idx_company_memberships_user_status', 'company_memberships', false,
            array['user_id', 'status']::text[], null::text),
        ('uq_company_memberships_approved_user', 'company_memberships', true,
            array['user_id']::text[], 'status=''APPROVED''::company_membership_status_type'),
        ('idx_inspections_assigned_inspector', 'inspections', false,
            array['assigned_inspector_id']::text[], null::text),
        ('idx_rag_documents_embedding_status', 'rag_documents', false,
            array['embedding_status']::text[], null::text),
        ('idx_rag_documents_target_collection', 'rag_documents', false,
            array['target_collection']::text[], null::text),
        ('uq_user_plans_active_user', 'user_plans', true,
            array['user_id']::text[], 'status=''ACTIVE''::user_plan_status_type'),
        ('uq_user_plans_active_company', 'user_plans', true,
            array['company_id']::text[], 'status=''ACTIVE''::user_plan_status_type')
    ) as expected(index_name, table_name, is_unique, column_names, predicate)
    left join pg_class index_class
      on index_class.relname = expected.index_name
     and index_class.relnamespace = 'public'::regnamespace
    left join pg_index index_meta on index_meta.indexrelid = index_class.oid
    left join pg_class table_class on table_class.oid = index_meta.indrelid
    left join lateral (
        select array_agg(attribute.attname::text order by key.ordinality) as names
        from unnest(index_meta.indkey::smallint[]) with ordinality as key(attnum, ordinality)
        join pg_attribute attribute
          on attribute.attrelid = index_meta.indrelid
         and attribute.attnum = key.attnum
        where key.ordinality <= index_meta.indnkeyatts
    ) indexed_columns on true
    where index_class.oid is null
       or not coalesce(index_meta.indisvalid, false)
       or not coalesce(index_meta.indisready, false)
       or table_class.relnamespace <> 'public'::regnamespace
       or table_class.relname <> expected.table_name
       or index_meta.indisunique <> expected.is_unique
       or indexed_columns.names is distinct from expected.column_names
       or regexp_replace(
              pg_get_expr(index_meta.indpred, index_meta.indrelid),
              '[[:space:]()]', '', 'g') is distinct from expected.predicate;

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

    -- 반대 방향 검증: company_id가 세팅된 모든 사용자가 그 회사에 유효한 APPROVED 멤버십을
    -- 가지고 있는지 확인한다. 격리된 PENDING 사용자의 레거시 포인터가 남으면 users.company_id를
    -- 인가 근거로 쓰는 기존 경로에서 승인 없이 회사 소속으로 오인될 수 있다.
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
        raise exception 'users.company_id set without a matching valid APPROVED membership remain';
    end if;

    if not exists (
        select 1
        from pg_constraint constraint_meta
        where constraint_meta.conname = 'fk_inspections_assigned_inspector'
          and constraint_meta.contype = 'f'
          and constraint_meta.conrelid = 'inspections'::regclass
          and constraint_meta.confrelid = 'users'::regclass
          and constraint_meta.convalidated
          and constraint_meta.confmatchtype = 's'
          and constraint_meta.confupdtype = 'a'
          and constraint_meta.confdeltype = 'a'
          and (
              select array_agg(attribute.attname::text order by key.ordinality)
              from unnest(constraint_meta.conkey) with ordinality as key(attnum, ordinality)
              join pg_attribute attribute
                on attribute.attrelid = constraint_meta.conrelid
               and attribute.attnum = key.attnum
          ) = array['assigned_inspector_id']
          and (
              select array_agg(attribute.attname::text order by key.ordinality)
              from unnest(constraint_meta.confkey) with ordinality as key(attnum, ordinality)
              join pg_attribute attribute
                on attribute.attrelid = constraint_meta.confrelid
               and attribute.attnum = key.attnum
          ) = array['id']
    ) then
        raise exception 'assigned inspector foreign key is missing, invalid, or points to the wrong key';
    end if;

    select string_agg(expected.trigger_name, ', ' order by expected.trigger_name)
    into invalid_triggers
    from (values
        ('trg_users_set_updated_at', 'users'),
        ('trg_companies_set_updated_at', 'companies'),
        ('trg_company_memberships_set_updated_at', 'company_memberships'),
        ('trg_plans_set_updated_at', 'plans'),
        ('trg_facilities_set_updated_at', 'facilities'),
        ('trg_reports_set_updated_at', 'reports'),
        ('trg_bot_scenarios_set_updated_at', 'bot_scenarios')
    ) as expected(trigger_name, table_name)
    left join pg_trigger actual
      on actual.tgname = expected.trigger_name
     and actual.tgrelid = to_regclass('public.' || expected.table_name)
     and actual.tgfoid = to_regprocedure('public.set_updated_at()')
     and actual.tgtype = 19
     and actual.tgenabled in ('O', 'A')
     and not actual.tgisinternal
    where actual.oid is null;

    if invalid_triggers is not null then
        raise exception 'required updated_at triggers are missing or misconfigured: %', invalid_triggers;
    end if;
end
$verification$;

select true as ha25_schema_ready;
