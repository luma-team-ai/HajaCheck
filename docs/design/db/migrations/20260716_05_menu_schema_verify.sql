-- 메뉴 스키마 배포 후 읽기 전용 검증. 한 행의 menu_schema_ready=true가 반환되어야 한다.

do $verification$
declare
    actual_labels text[];
    invalid_columns text;
    invalid_constraints text;
    invalid_foreign_keys text;
    invalid_indexes text;
begin
    if to_regtype('public.menu_node_type') is null then
        raise exception 'public.menu_node_type is missing';
    end if;

    select array_agg(enum_meta.enumlabel::text order by enum_meta.enumsortorder)
    into actual_labels
    from pg_enum enum_meta
    where enum_meta.enumtypid = to_regtype('public.menu_node_type');

    if actual_labels is distinct from array['GROUP', 'INTERNAL', 'EXTERNAL'] then
        raise exception 'public.menu_node_type labels do not match the canonical DDL: %', actual_labels;
    end if;

    if to_regclass('public.menus') is null
       or to_regclass('public.menu_role_access') is null then
        raise exception 'menus or menu_role_access table is missing';
    end if;

    select string_agg(expected.table_name || '.' || expected.column_name, ', '
                      order by expected.table_name, expected.ordinal_position)
    into invalid_columns
    from (values
        ('menus', 1,  'id',                  'bigint',                   null::text,        'NO',  null::integer, null::text, 'ALWAYS'),
        ('menus', 2,  'code',                'character varying',        null::text,        'NO',  100,           null::text, null::text),
        ('menus', 3,  'name',                'character varying',        null::text,        'NO',  100,           null::text, null::text),
        ('menus', 4,  'menu_type',           'USER-DEFINED',             'menu_node_type',  'NO',  null::integer, null::text, null::text),
        ('menus', 5,  'parent_id',           'bigint',                   null::text,        'YES', null::integer, null::text, null::text),
        ('menus', 6,  'path',                'character varying',        null::text,        'YES', 500,           null::text, null::text),
        ('menus', 7,  'active_path_pattern', 'character varying',        null::text,        'YES', 500,           null::text, null::text),
        ('menus', 8,  'icon_key',            'character varying',        null::text,        'YES', 100,           null::text, null::text),
        ('menus', 9,  'icon_url',            'character varying',        null::text,        'YES', 500,           null::text, null::text),
        ('menus', 10, 'sort_order',           'integer',                  null::text,        'NO',  null::integer, '0',        null::text),
        ('menus', 11, 'is_visible',           'boolean',                  null::text,        'NO',  null::integer, 'true',     null::text),
        ('menus', 12, 'is_enabled',           'boolean',                  null::text,        'NO',  null::integer, 'true',     null::text),
        ('menus', 13, 'opens_new_tab',        'boolean',                  null::text,        'NO',  null::integer, 'false',    null::text),
        ('menus', 14, 'description',          'character varying',        null::text,        'YES', 500,           null::text, null::text),
        ('menus', 15, 'created_by',           'bigint',                   null::text,        'YES', null::integer, null::text, null::text),
        ('menus', 16, 'updated_by',           'bigint',                   null::text,        'YES', null::integer, null::text, null::text),
        ('menus', 17, 'created_at',           'timestamp with time zone', null::text,        'NO',  null::integer, 'now',      null::text),
        ('menus', 18, 'updated_at',           'timestamp with time zone', null::text,        'NO',  null::integer, 'now',      null::text),
        ('menu_role_access', 1, 'menu_id',    'bigint',                   null::text,        'NO',  null::integer, null::text, null::text),
        ('menu_role_access', 2, 'role',       'USER-DEFINED',             'role_type',       'NO',  null::integer, null::text, null::text),
        ('menu_role_access', 3, 'created_by', 'bigint',                   null::text,        'YES', null::integer, null::text, null::text),
        ('menu_role_access', 4, 'created_at', 'timestamp with time zone', null::text,        'NO',  null::integer, 'now',      null::text)
    ) as expected(
        table_name, ordinal_position, column_name, data_type, udt_name, is_nullable,
        maximum_length, normalized_default, identity_generation)
    left join information_schema.columns actual
      on actual.table_schema = 'public'
     and actual.table_name = expected.table_name
     and actual.column_name = expected.column_name
     and actual.ordinal_position = expected.ordinal_position
     and actual.data_type = expected.data_type
     and actual.is_nullable = expected.is_nullable
     and actual.character_maximum_length is not distinct from expected.maximum_length
     and actual.identity_generation is not distinct from expected.identity_generation
     and (
         expected.normalized_default is null
         or regexp_replace(lower(coalesce(actual.column_default, '')), '[[:space:]()'']', '', 'g')
            = expected.normalized_default
     )
     and (
         expected.udt_name is null
         or (actual.udt_schema = 'public' and actual.udt_name = expected.udt_name)
     )
    where actual.column_name is null;

    if invalid_columns is not null then
        raise exception 'menu schema columns are missing or semantically different: %', invalid_columns;
    end if;

    select string_agg(expected.constraint_name, ', ' order by expected.constraint_name)
    into invalid_constraints
    from (values
        ('menus_pkey', 'menus', 'p', null::text, null::text, null::text),
        ('menus_code_key', 'menus', 'u', null::text, null::text, null::text),
        ('fk_menus_parent', 'menus', 'f', 'menus', 'r', null::text),
        ('menus_created_by_fkey', 'menus', 'f', 'users', 'a', null::text),
        ('menus_updated_by_fkey', 'menus', 'f', 'users', 'a', null::text),
        ('ck_menus_not_self_parent', 'menus', 'c', null::text, null::text,
            'CHECK(((parent_idISNULL)OR(parent_id<>id)))'),
        ('ck_menus_sort_order_nonnegative', 'menus', 'c', null::text, null::text,
            'CHECK((sort_order>=0))'),
        ('ck_menus_icon_single', 'menus', 'c', null::text, null::text,
            'CHECK((((menu_type=''GROUP''::menu_node_type)AND(num_nonnulls(icon_key,icon_url)<=1))OR((menu_type<>''GROUP''::menu_node_type)AND(num_nonnulls(icon_key,icon_url)=1))))'),
        ('ck_menus_path_by_type', 'menus', 'c', null::text, null::text,
            'CHECK((((menu_type=''GROUP''::menu_node_type)AND(pathISNULL))OR((menu_type<>''GROUP''::menu_node_type)AND(pathISNOTNULL))))'),
        ('menu_role_access_pkey', 'menu_role_access', 'p', null::text, null::text, null::text),
        ('menu_role_access_menu_id_fkey', 'menu_role_access', 'f', 'menus', 'c', null::text),
        ('menu_role_access_created_by_fkey', 'menu_role_access', 'f', 'users', 'a', null::text)
    ) as expected(constraint_name, table_name, constraint_type, referenced_table, delete_action, definition)
    left join pg_constraint actual
     on actual.conname = expected.constraint_name
     and actual.conrelid = to_regclass('public.' || expected.table_name)
     and actual.contype::text = expected.constraint_type
     and actual.convalidated
     and (
         expected.referenced_table is null
         or actual.confrelid = to_regclass('public.' || expected.referenced_table)
     )
     and (
         expected.delete_action is null
         or actual.confdeltype::text = expected.delete_action
     )
     and (
         expected.definition is null
         or regexp_replace(pg_get_constraintdef(actual.oid), '[[:space:]()]', '', 'g')
            = regexp_replace(expected.definition, '[[:space:]()]', '', 'g')
     )
    where actual.oid is null;

    if invalid_constraints is not null then
        raise exception 'menu schema constraints are missing or semantically different: %', invalid_constraints;
    end if;

    select string_agg(expected.constraint_name, ', ' order by expected.constraint_name)
    into invalid_foreign_keys
    from (values
        ('fk_menus_parent', 'menus', array['parent_id']::text[],
            'menus', array['id']::text[], 'r'),
        ('menus_created_by_fkey', 'menus', array['created_by']::text[],
            'users', array['id']::text[], 'a'),
        ('menus_updated_by_fkey', 'menus', array['updated_by']::text[],
            'users', array['id']::text[], 'a'),
        ('menu_role_access_menu_id_fkey', 'menu_role_access', array['menu_id']::text[],
            'menus', array['id']::text[], 'c'),
        ('menu_role_access_created_by_fkey', 'menu_role_access', array['created_by']::text[],
            'users', array['id']::text[], 'a')
    ) as expected(
        constraint_name, table_name, source_columns,
        referenced_table, referenced_columns, delete_action)
    left join pg_constraint actual
      on actual.conname = expected.constraint_name
     and actual.contype = 'f'
     and actual.conrelid = to_regclass('public.' || expected.table_name)
     and actual.confrelid = to_regclass('public.' || expected.referenced_table)
     and actual.confdeltype::text = expected.delete_action
     and actual.confupdtype = 'a'
     and actual.confmatchtype = 's'
     and actual.convalidated
    left join lateral (
        select array_agg(attribute.attname::text order by key.ordinality) as names
        from unnest(actual.conkey) with ordinality as key(attnum, ordinality)
        join pg_attribute attribute
          on attribute.attrelid = actual.conrelid
         and attribute.attnum = key.attnum
    ) source_columns on true
    left join lateral (
        select array_agg(attribute.attname::text order by key.ordinality) as names
        from unnest(actual.confkey) with ordinality as key(attnum, ordinality)
        join pg_attribute attribute
          on attribute.attrelid = actual.confrelid
         and attribute.attnum = key.attnum
    ) referenced_columns on true
    where actual.oid is null
       or source_columns.names is distinct from expected.source_columns
       or referenced_columns.names is distinct from expected.referenced_columns;

    if invalid_foreign_keys is not null then
        raise exception 'menu schema foreign keys are missing or semantically different: %', invalid_foreign_keys;
    end if;

    if not exists (
        select 1
        from pg_constraint constraint_meta
        where constraint_meta.conname = 'menus_pkey'
          and constraint_meta.conrelid = 'public.menus'::regclass
          and (
              select array_agg(attribute.attname::text order by key.ordinality)
              from unnest(constraint_meta.conkey) with ordinality as key(attnum, ordinality)
              join pg_attribute attribute
                on attribute.attrelid = constraint_meta.conrelid
               and attribute.attnum = key.attnum
          ) = array['id']
    ) or not exists (
        select 1
        from pg_constraint constraint_meta
        where constraint_meta.conname = 'menus_code_key'
          and constraint_meta.conrelid = 'public.menus'::regclass
          and (
              select array_agg(attribute.attname::text order by key.ordinality)
              from unnest(constraint_meta.conkey) with ordinality as key(attnum, ordinality)
              join pg_attribute attribute
                on attribute.attrelid = constraint_meta.conrelid
               and attribute.attnum = key.attnum
          ) = array['code']
    ) or not exists (
        select 1
        from pg_constraint constraint_meta
        where constraint_meta.conname = 'menu_role_access_pkey'
          and constraint_meta.conrelid = 'public.menu_role_access'::regclass
          and (
              select array_agg(attribute.attname::text order by key.ordinality)
              from unnest(constraint_meta.conkey) with ordinality as key(attnum, ordinality)
              join pg_attribute attribute
                on attribute.attrelid = constraint_meta.conrelid
               and attribute.attnum = key.attnum
          ) = array['menu_id', 'role']
    ) then
        raise exception 'menu schema primary/unique key columns do not match the canonical DDL';
    end if;

    select string_agg(expected.index_name, ', ' order by expected.index_name)
    into invalid_indexes
    from (values
        ('idx_menus_parent', 'menus', array['parent_id']::text[]),
        ('idx_menu_role_access_role', 'menu_role_access', array['role', 'menu_id']::text[])
    ) as expected(index_name, table_name, column_names)
    left join pg_class index_class
      on index_class.relname = expected.index_name
     and index_class.relnamespace = 'public'::regnamespace
    left join pg_index index_meta on index_meta.indexrelid = index_class.oid
    left join pg_class table_class on table_class.oid = index_meta.indrelid
    left join pg_am access_method on access_method.oid = index_class.relam
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
       or not coalesce(index_meta.indislive, false)
       or table_class.relnamespace <> 'public'::regnamespace
       or table_class.relname <> expected.table_name
       or index_meta.indisunique
       or access_method.amname <> 'btree'
       or index_meta.indpred is not null
       or index_meta.indexprs is not null
       or indexed_columns.names is distinct from expected.column_names;

    if invalid_indexes is not null then
        raise exception 'menu schema indexes are missing or invalid: %', invalid_indexes;
    end if;

    if not exists (
        select 1
        from pg_trigger trigger_meta
        where trigger_meta.tgname = 'trg_menus_set_updated_at'
          and trigger_meta.tgrelid = 'public.menus'::regclass
          and trigger_meta.tgfoid = to_regprocedure('public.set_updated_at()')
          and trigger_meta.tgtype = 19
          and trigger_meta.tgenabled in ('O', 'A')
          and not trigger_meta.tgisinternal
    ) then
        raise exception 'trg_menus_set_updated_at is missing or misconfigured';
    end if;

    if exists (
        select 1
        from public.menu_role_access access_rule
        join public.menus menu on menu.id = access_rule.menu_id
        where menu.menu_type = 'GROUP'::public.menu_node_type
    ) then
        raise exception 'GROUP menus must not have direct menu_role_access rows';
    end if;
end
$verification$;

select true as menu_schema_ready;
