-- API 시스템 로그 테이블 추가: API 호출 결과가 4xx(WARN) 또는 5xx(ERROR)인 요청을 기록한다.
-- 신규 빈 테이블과 일반 인덱스만 추가하므로 데이터 백필과 concurrent 인덱스 생성은 없다.
-- 같은 파일을 재실행할 때는 기존 테이블·제약·인덱스의 의미가 캐노니컬 DDL과 정확히 같아야 한다.
--
-- Run this file with psql autocommit enabled and ON_ERROR_STOP=1.

select pg_advisory_lock(hashtext('hajacheck:api-system-logs:migration'));

do $migration$
begin
    if to_regclass('public.api_system_logs') is null then
        create table public.api_system_logs
        (
            id             bigint generated always as identity
                primary key,
            level          varchar(10)                            not null,
            request_id     varchar(100)                           not null,
            http_method    varchar(10)                            not null,
            endpoint       varchar(500)                           not null,
            http_status    smallint                               not null,
            error_code     varchar(100),
            message        varchar(500),
            exception_type varchar(255),
            user_id        bigint,
            duration_ms    bigint                                 not null,
            client_ip      inet,
            created_at     timestamp with time zone default now() not null,
            constraint ck_api_system_logs_level
                check (level in ('WARN', 'ERROR')),
            constraint ck_api_system_logs_level_http_status
                check (
                    (level = 'WARN' and http_status between 400 and 499)
                    or (level = 'ERROR' and http_status between 500 and 599)
                ),
            constraint ck_api_system_logs_duration
                check (duration_ms >= 0)
        );

        alter table public.api_system_logs owner to postgres;
    end if;
end
$migration$;

do $migration$
declare
    mismatch_count integer;
    actual_owner text;
    actual_primary_key text[];
begin
    if not exists (
        select 1
        from pg_class table_meta
        join pg_namespace namespace on namespace.oid = table_meta.relnamespace
        where namespace.nspname = 'public'
          and table_meta.relname = 'api_system_logs'
          and table_meta.relkind = 'r'
          and table_meta.relpersistence = 'p'
    ) then
        raise exception 'public.api_system_logs must be a permanent ordinary table';
    end if;

    select pg_get_userbyid(table_meta.relowner)
    into actual_owner
    from pg_class table_meta
    where table_meta.oid = 'public.api_system_logs'::regclass;

    if actual_owner is distinct from 'postgres' then
        raise exception 'public.api_system_logs owner differs from canonical DDL: %', actual_owner;
    end if;

    with expected(column_name, data_type, is_not_null, default_expression, identity_kind) as (
        values
            ('id', 'bigint', true, null::text, 'a'),
            ('level', 'character varying(10)', true, null::text, ''),
            ('request_id', 'character varying(100)', true, null::text, ''),
            ('http_method', 'character varying(10)', true, null::text, ''),
            ('endpoint', 'character varying(500)', true, null::text, ''),
            ('http_status', 'smallint', true, null::text, ''),
            ('error_code', 'character varying(100)', false, null::text, ''),
            ('message', 'character varying(500)', false, null::text, ''),
            ('exception_type', 'character varying(255)', false, null::text, ''),
            ('user_id', 'bigint', false, null::text, ''),
            ('duration_ms', 'bigint', true, null::text, ''),
            ('client_ip', 'inet', false, null::text, ''),
            ('created_at', 'timestamp with time zone', true, 'now()', '')
    ), actual as (
        select attribute.attname::text as column_name,
               format_type(attribute.atttypid, attribute.atttypmod) as data_type,
               attribute.attnotnull as is_not_null,
               pg_get_expr(default_meta.adbin, default_meta.adrelid, true) as default_expression,
               attribute.attidentity::text as identity_kind
        from pg_attribute attribute
        left join pg_attrdef default_meta
          on default_meta.adrelid = attribute.attrelid
         and default_meta.adnum = attribute.attnum
        where attribute.attrelid = 'public.api_system_logs'::regclass
          and attribute.attnum > 0
          and not attribute.attisdropped
    )
    select count(*) into mismatch_count
    from (
        (select * from expected except select * from actual)
        union all
        (select * from actual except select * from expected)
    ) drift;

    if mismatch_count <> 0 then
        raise exception 'public.api_system_logs columns differ from canonical DDL';
    end if;

    select array_agg(attribute.attname::text order by key.ordinality)
    into actual_primary_key
    from pg_constraint constraint_meta
    cross join lateral unnest(constraint_meta.conkey) with ordinality as key(attnum, ordinality)
    join pg_attribute attribute
      on attribute.attrelid = constraint_meta.conrelid
     and attribute.attnum = key.attnum
    where constraint_meta.conrelid = 'public.api_system_logs'::regclass
      and constraint_meta.contype = 'p'
    group by constraint_meta.oid;

    if actual_primary_key is distinct from array['id'] then
        raise exception 'public.api_system_logs primary key differs from canonical DDL: %',
            actual_primary_key;
    end if;

    create temporary table expected_api_system_logs_checks
    (
        level       varchar(10),
        http_status smallint,
        duration_ms bigint,
        constraint ck_api_system_logs_level
            check (level in ('WARN', 'ERROR')),
        constraint ck_api_system_logs_level_http_status
            check (
                (level = 'WARN' and http_status between 400 and 499)
                or (level = 'ERROR' and http_status between 500 and 599)
            ),
        constraint ck_api_system_logs_duration
            check (duration_ms >= 0)
    ) on commit drop;

    with expected as (
        select constraint_meta.conname,
               pg_get_constraintdef(constraint_meta.oid, true) as definition
        from pg_constraint constraint_meta
        where constraint_meta.conrelid = 'expected_api_system_logs_checks'::regclass
    ), actual as (
        select constraint_meta.conname,
               pg_get_constraintdef(constraint_meta.oid, true) as definition
        from pg_constraint constraint_meta
        where constraint_meta.conrelid = 'public.api_system_logs'::regclass
          and constraint_meta.contype = 'c'
    )
    select count(*) into mismatch_count
    from (
        (select * from expected except select * from actual)
        union all
        (select * from actual except select * from expected)
    ) drift;

    if mismatch_count <> 0 then
        raise exception 'public.api_system_logs check constraints differ from canonical DDL';
    end if;

    if exists (
        select 1
        from pg_constraint constraint_meta
        where constraint_meta.conrelid = 'public.api_system_logs'::regclass
          and constraint_meta.contype = 'f'
    ) then
        raise exception 'public.api_system_logs must not have foreign keys; user_id is a logical reference only';
    end if;

    if (
        select count(*)
        from pg_constraint constraint_meta
        where constraint_meta.conrelid = 'public.api_system_logs'::regclass
    ) <> 4 then
        raise exception 'public.api_system_logs has unexpected constraints';
    end if;
end
$migration$;

comment on table public.api_system_logs is 'API 호출 결과가 4xx 또는 5xx인 요청의 시스템 로그를 요청당 최대 한 행으로 기록한다.';
comment on column public.api_system_logs.id is 'API 시스템 로그 식별자';
comment on column public.api_system_logs.level is 'HTTP 응답 상태에 따른 로그 레벨(WARN=4xx, ERROR=5xx)';
comment on column public.api_system_logs.request_id is 'API 요청 추적 식별자. 애플리케이션은 요청당 최대 한 로그 행만 기록하되 DB UNIQUE로 강제하지 않는다';
comment on column public.api_system_logs.http_method is 'API 요청 HTTP 메서드';
comment on column public.api_system_logs.endpoint is '식별자와 개인정보를 제거한 API 엔드포인트 패턴';
comment on column public.api_system_logs.http_status is '최종 HTTP 응답 상태 코드';
comment on column public.api_system_logs.error_code is '애플리케이션 공통 오류 코드';
comment on column public.api_system_logs.message is '민감정보를 제거한 오류 요약 메시지';
comment on column public.api_system_logs.exception_type is '오류를 발생시킨 예외 클래스명';
comment on column public.api_system_logs.user_id is '요청 사용자 식별자. 사용자 삭제 후에도 로그를 보존하기 위해 users 외래키를 두지 않는다';
comment on column public.api_system_logs.duration_ms is 'API 요청 처리 시간(밀리초)';
comment on column public.api_system_logs.client_ip is '요청 클라이언트 IP 주소';
comment on column public.api_system_logs.created_at is 'API 시스템 로그 생성 시각';

create index if not exists idx_api_system_logs_created_at
    on public.api_system_logs (created_at desc);

create index if not exists idx_api_system_logs_level_created_at
    on public.api_system_logs (level, created_at desc);

create index if not exists idx_api_system_logs_request_id
    on public.api_system_logs (request_id);

do $migration$
declare
    mismatch_count integer;
begin
    with expected(index_name, key_columns, index_options) as (
        values
            ('idx_api_system_logs_created_at', array['created_at'], '3'),
            ('idx_api_system_logs_level_created_at', array['level', 'created_at'], '0 3'),
            ('idx_api_system_logs_request_id', array['request_id'], '0')
    ), actual as (
        select index_class.relname::text as index_name,
               array_agg(attribute.attname::text order by key.ordinality) as key_columns,
               index_meta.indoption::text as index_options
        from pg_index index_meta
        join pg_class index_class on index_class.oid = index_meta.indexrelid
        join pg_class table_meta on table_meta.oid = index_meta.indrelid
        join pg_am access_method on access_method.oid = index_class.relam
        cross join lateral unnest(index_meta.indkey) with ordinality as key(attnum, ordinality)
        join pg_attribute attribute
          on attribute.attrelid = table_meta.oid
         and attribute.attnum = key.attnum
        where table_meta.oid = 'public.api_system_logs'::regclass
          and not index_meta.indisprimary
          and not index_meta.indisunique
          and index_meta.indisvalid
          and index_meta.indisready
          and index_meta.indpred is null
          and index_meta.indexprs is null
          and index_meta.indnkeyatts = index_meta.indnatts
          and access_method.amname = 'btree'
        group by index_class.relname, index_meta.indoption
    )
    select count(*) into mismatch_count
    from (
        (select * from expected except select * from actual)
        union all
        (select * from actual except select * from expected)
    ) drift;

    if mismatch_count <> 0 then
        raise exception 'public.api_system_logs indexes differ from canonical DDL';
    end if;
end
$migration$;

select pg_advisory_unlock(hashtext('hajacheck:api-system-logs:migration'));
