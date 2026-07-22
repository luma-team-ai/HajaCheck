-- #568 / HAJA-327 점검·관리자 스키마 정합화
--
-- V1은 동결된 baseline이므로 이 파일에서 기존 DB와 신규 DB(V1→V5)의 최종 상태를 함께 맞춘다.

-- 운영 트래픽을 무기한 대기시키지 않는다. 특히 마지막 inspections ALTER는 짧게 락을 얻지 못하면
-- 배포를 실패시켜 재시도할 수 있게 한다.
set local lock_timeout = '5s';

alter type public.role_type add value if not exists 'PLATFORM_ADMIN';

do $$
declare
    actual_labels text[];
begin
    select array_agg(e.enumlabel order by e.enumsortorder)
      into actual_labels
      from pg_enum e
      join pg_type t on t.oid = e.enumtypid
      join pg_namespace n on n.oid = t.typnamespace
     where n.nspname = 'public'
       and t.typname = 'role_type';

    if actual_labels is distinct from
       array['ADMIN', 'INSPECTOR', 'USER', 'COUNSELOR', 'PLATFORM_ADMIN']::text[] then
        raise exception 'role_type enum labels mismatch: %', actual_labels;
    end if;
end
$$;

comment on type public.role_type is
    '사용자 권한 역할(관리자/플랫폼 관리자/검사자/일반 사용자/상담사)';

do $$
begin
    if not exists (
        select 1
        from pg_type t
        join pg_namespace n on n.oid = t.typnamespace
        where n.nspname = 'public'
          and t.typname = 'inspection_type'
    ) then
        create type public.inspection_type as enum ('REGULAR', 'DETAILED', 'EMERGENCY');
    end if;
end
$$;

do $$
declare
    actual_labels text[];
begin
    select array_agg(e.enumlabel order by e.enumsortorder)
      into actual_labels
      from pg_enum e
      join pg_type t on t.oid = e.enumtypid
      join pg_namespace n on n.oid = t.typnamespace
     where n.nspname = 'public'
       and t.typname = 'inspection_type';

    if actual_labels is distinct from array['REGULAR', 'DETAILED', 'EMERGENCY']::text[] then
        raise exception 'inspection_type enum labels mismatch: %', actual_labels;
    end if;
end
$$;

comment on type public.inspection_type is '점검 유형(정기/정밀/긴급)';

alter table public.plans
    alter column max_seats drop not null,
    alter column max_seats drop default;

-- 기존 Enterprise 무제한 표현에만 사용한 sentinel을 SQL NULL 의미로 정규화한다.
update public.plans
   set max_seats = null
 where name = 'ENTERPRISE'::public.plan_name_type
   and max_seats = 1000000;

comment on column public.plans.max_seats is
    '요금제에서 허용하는 최대 사용자 좌석 수(NULL은 무제한)';

-- notifications는 발행된 알림 이력이다. 아래 테이블은 사용자·시설별 점검 알림 정책을 별도로 보관한다.
create table if not exists public.inspection_notification_settings
(
    id                         bigint generated always as identity
        primary key,
    user_id                    bigint                   not null
        constraint fk_inspection_notification_settings_user
            references public.users
            on delete cascade,
    facility_id                bigint                   not null
        constraint fk_inspection_notification_settings_facility
            references public.facilities
            on delete cascade,
    notify_before_enabled      boolean                  default true  not null,
    notify_before_days         smallint                 default 7     not null,
    warn_on_overdue_enabled    boolean                  default false not null,
    created_at                 timestamp with time zone default now() not null,
    updated_at                 timestamp with time zone default now() not null,
    constraint uk_inspection_notification_settings_user_facility
        unique (user_id, facility_id),
    constraint ck_inspection_notification_settings_before_days
        check (notify_before_days between 1 and 365)
);

-- CREATE TABLE IF NOT EXISTS는 동명 테이블의 정의를 비교하지 않는다. baseline-on-existing 경로에서
-- 불완전한 사전 생성 테이블을 정상 스키마로 승인하지 않도록 컬럼 의미를 정확히 검증한다.
do $$
begin
    if not exists (
        select 1
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public'
           and c.relname = 'inspection_notification_settings'
           and c.relkind = 'r'
           and c.relpersistence = 'p'
           and not c.relispartition
           and not c.relrowsecurity
           and not c.relforcerowsecurity
           and not exists (
                select 1
                  from pg_inherits i
                 where i.inhrelid = c.oid
                    or i.inhparent = c.oid
           )
    ) then
        raise exception 'inspection_notification_settings table semantics mismatch';
    end if;

    if (select count(*)
          from pg_attribute a
         where a.attrelid = 'public.inspection_notification_settings'::regclass
           and a.attnum > 0
           and not a.attisdropped) <> 8
       or exists (
            select 1
              from (values
                    ('id'::text, 'bigint'::text, true, 'a'::"char", null::text),
                    ('user_id', 'bigint', true, ''::"char", null),
                    ('facility_id', 'bigint', true, ''::"char", null),
                    ('notify_before_enabled', 'boolean', true, ''::"char", 'true'),
                    ('notify_before_days', 'smallint', true, ''::"char", '7'),
                    ('warn_on_overdue_enabled', 'boolean', true, ''::"char", 'false'),
                    ('created_at', 'timestamp with time zone', true, ''::"char", 'now()'),
                    ('updated_at', 'timestamp with time zone', true, ''::"char", 'now()')
                   ) expected(column_name, data_type, is_not_null, identity_kind, default_expression)
              left join pg_attribute a
                on a.attrelid = 'public.inspection_notification_settings'::regclass
               and a.attname = expected.column_name
               and a.attnum > 0
               and not a.attisdropped
              left join pg_attrdef d
                on d.adrelid = a.attrelid
               and d.adnum = a.attnum
             where a.attname is null
                or format_type(a.atttypid, a.atttypmod) is distinct from expected.data_type
                or a.attnotnull is distinct from expected.is_not_null
                or a.attidentity is distinct from expected.identity_kind
                or pg_get_expr(d.adbin, d.adrelid) is distinct from expected.default_expression
       ) then
        raise exception 'inspection_notification_settings column semantics mismatch';
    end if;

    if (select count(*)
          from pg_constraint c
         where c.conrelid = 'public.inspection_notification_settings'::regclass) <> 5 then
        raise exception 'inspection_notification_settings constraint count mismatch';
    end if;

    if not exists (
        select 1
          from pg_constraint c
         where c.conrelid = 'public.inspection_notification_settings'::regclass
           and c.contype = 'p'
           and c.convalidated
           and not c.condeferrable
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(c.conkey) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key.attnum)
               = array['id']::name[]
    ) then
        raise exception 'inspection_notification_settings primary key semantics mismatch';
    end if;

    if not exists (
        select 1
          from pg_constraint c
         where c.conrelid = 'public.inspection_notification_settings'::regclass
           and c.conname = 'fk_inspection_notification_settings_user'
           and c.contype = 'f'
           and c.confrelid = 'public.users'::regclass
           and c.confdeltype = 'c'
           and c.confupdtype = 'a'
           and c.convalidated
           and not c.condeferrable
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(c.conkey) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key.attnum)
               = array['user_id']::name[]
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(c.confkey) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = c.confrelid and a.attnum = key.attnum)
               = array['id']::name[]
    ) then
        raise exception 'inspection_notification_settings user foreign key semantics mismatch';
    end if;

    if not exists (
        select 1
          from pg_constraint c
         where c.conrelid = 'public.inspection_notification_settings'::regclass
           and c.conname = 'fk_inspection_notification_settings_facility'
           and c.contype = 'f'
           and c.confrelid = 'public.facilities'::regclass
           and c.confdeltype = 'c'
           and c.confupdtype = 'a'
           and c.convalidated
           and not c.condeferrable
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(c.conkey) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key.attnum)
               = array['facility_id']::name[]
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(c.confkey) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = c.confrelid and a.attnum = key.attnum)
               = array['id']::name[]
    ) then
        raise exception 'inspection_notification_settings facility foreign key semantics mismatch';
    end if;

    if not exists (
        select 1
          from pg_constraint c
         where c.conrelid = 'public.inspection_notification_settings'::regclass
           and c.conname = 'uk_inspection_notification_settings_user_facility'
           and c.contype = 'u'
           and c.convalidated
           and not c.condeferrable
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(c.conkey) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key.attnum)
               = array['user_id', 'facility_id']::name[]
    ) then
        raise exception 'inspection_notification_settings unique constraint semantics mismatch';
    end if;

    if not exists (
        select 1
          from pg_constraint c
         where c.conrelid = 'public.inspection_notification_settings'::regclass
           and c.conname = 'ck_inspection_notification_settings_before_days'
           and c.contype = 'c'
           and c.convalidated
           and pg_get_constraintdef(c.oid) =
               'CHECK (((notify_before_days >= 1) AND (notify_before_days <= 365)))'
    ) then
        raise exception 'inspection_notification_settings check constraint semantics mismatch';
    end if;
end
$$;

comment on table public.inspection_notification_settings is
    '사용자·시설별 점검 예정 및 기한 경과 알림 설정';
comment on column public.inspection_notification_settings.user_id is '알림 설정 사용자 식별자';
comment on column public.inspection_notification_settings.facility_id is '알림 설정 대상 시설 식별자';
comment on column public.inspection_notification_settings.notify_before_enabled is '점검 예정 사전 알림 사용 여부';
comment on column public.inspection_notification_settings.notify_before_days is '점검 예정일 전 알림 일수(1~365일)';
comment on column public.inspection_notification_settings.warn_on_overdue_enabled is '점검 예정일 경과 알림 사용 여부';
comment on column public.inspection_notification_settings.created_at is '알림 설정 생성 시각';
comment on column public.inspection_notification_settings.updated_at is '알림 설정 최종 수정 시각';

create index if not exists idx_inspection_notification_settings_facility
    on public.inspection_notification_settings (facility_id);

do $$
begin
    if not exists (
        select 1
          from pg_trigger t
         where t.tgrelid = 'public.inspection_notification_settings'::regclass
           and t.tgname = 'trg_inspection_notification_settings_set_updated_at'
           and not t.tgisinternal
    ) then
        execute 'create trigger trg_inspection_notification_settings_set_updated_at
                 before update on public.inspection_notification_settings
                 for each row execute procedure public.set_updated_at()';
    end if;
end
$$;

do $$
begin
    if (select count(*)
          from pg_index i
         where i.indrelid = 'public.inspection_notification_settings'::regclass) <> 3
       or exists (
            select 1
              from pg_index i
              join pg_class idx on idx.oid = i.indexrelid
             where i.indrelid = 'public.inspection_notification_settings'::regclass
               and idx.relname not in (
                   'inspection_notification_settings_pkey',
                   'uk_inspection_notification_settings_user_facility',
                   'idx_inspection_notification_settings_facility')
       ) then
        raise exception 'inspection_notification_settings index set mismatch';
    end if;

    if not exists (
        select 1
          from pg_index i
          join pg_class idx on idx.oid = i.indexrelid
          join pg_am am on am.oid = idx.relam
         where i.indrelid = 'public.inspection_notification_settings'::regclass
           and idx.relname = 'inspection_notification_settings_pkey'
           and am.amname = 'btree'
           and i.indisvalid
           and i.indisready
           and i.indislive
           and i.indisunique
           and i.indisprimary
           and not i.indisexclusion
           and i.indimmediate
           and i.indpred is null
           and i.indexprs is null
           and i.indnkeyatts = 1
           and i.indnatts = 1
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(i.indkey::smallint[]) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = i.indrelid and a.attnum = key.attnum)
               = array['id']::name[]
    ) then
        raise exception 'inspection_notification_settings primary key index semantics mismatch';
    end if;

    if not exists (
        select 1
          from pg_index i
          join pg_class idx on idx.oid = i.indexrelid
          join pg_am am on am.oid = idx.relam
         where i.indrelid = 'public.inspection_notification_settings'::regclass
           and idx.relname = 'uk_inspection_notification_settings_user_facility'
           and am.amname = 'btree'
           and i.indisvalid
           and i.indisready
           and i.indislive
           and i.indisunique
           and not i.indisprimary
           and not i.indisexclusion
           and i.indimmediate
           and i.indpred is null
           and i.indexprs is null
           and i.indnkeyatts = 2
           and i.indnatts = 2
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(i.indkey::smallint[]) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = i.indrelid and a.attnum = key.attnum)
               = array['user_id', 'facility_id']::name[]
    ) then
        raise exception 'inspection_notification_settings unique index semantics mismatch';
    end if;

    if not exists (
        select 1
          from pg_index i
          join pg_class idx on idx.oid = i.indexrelid
          join pg_am am on am.oid = idx.relam
         where i.indrelid = 'public.inspection_notification_settings'::regclass
           and idx.relname = 'idx_inspection_notification_settings_facility'
           and am.amname = 'btree'
           and i.indisvalid
           and i.indisready
           and i.indislive
           and not i.indisunique
           and i.indpred is null
           and i.indexprs is null
           and i.indnkeyatts = 1
           and i.indnatts = 1
           and (select array_agg(a.attname order by key.ordinality)
                  from unnest(i.indkey::smallint[]) with ordinality key(attnum, ordinality)
                  join pg_attribute a on a.attrelid = i.indrelid and a.attnum = key.attnum)
               = array['facility_id']::name[]
    ) then
        raise exception 'inspection_notification_settings index semantics mismatch';
    end if;

    if (select count(*)
          from pg_trigger t
         where t.tgrelid = 'public.inspection_notification_settings'::regclass
           and not t.tgisinternal) <> 1
       or exists (
            select 1
              from pg_trigger t
             where t.tgrelid = 'public.inspection_notification_settings'::regclass
               and not t.tgisinternal
               and t.tgname <> 'trg_inspection_notification_settings_set_updated_at'
       ) then
        raise exception 'inspection_notification_settings trigger set mismatch';
    end if;

    if not exists (
        select 1
          from pg_trigger t
         where t.tgrelid = 'public.inspection_notification_settings'::regclass
           and t.tgname = 'trg_inspection_notification_settings_set_updated_at'
           and not t.tgisinternal
           and t.tgenabled = 'O'
           and t.tgtype = 19
           and t.tgfoid = 'public.set_updated_at()'::regprocedure
           and t.tgnargs = 0
           and t.tgqual is null
           and cardinality(t.tgattr::smallint[]) = 0
    ) then
        raise exception 'inspection_notification_settings trigger semantics mismatch';
    end if;
end
$$;

comment on trigger trg_inspection_notification_settings_set_updated_at
    on public.inspection_notification_settings is
    '점검 알림 설정 변경 시 updated_at을 현재 시각으로 갱신한다.';

-- ACCESS EXCLUSIVE 락이 필요한 inspections 변경은 V5의 마지막에 둔다. PostgreSQL 16의 constant-default
-- fast path는 기존 행을 UPDATE하거나 NOT NULL 검증으로 스캔하지 않고 REGULAR 값을 메타데이터로 채운다.
alter table public.inspections
    add column if not exists type public.inspection_type
        default 'REGULAR'::public.inspection_type not null;

do $$
declare
    actual_type oid;
    actual_not_null boolean;
    actual_identity "char";
    actual_generated "char";
    actual_default text;
begin
    select a.atttypid,
           a.attnotnull,
           a.attidentity,
           a.attgenerated,
           replace(pg_get_expr(d.adbin, d.adrelid), 'public.', '')
      into actual_type, actual_not_null, actual_identity, actual_generated, actual_default
      from pg_attribute a
      left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
     where a.attrelid = 'public.inspections'::regclass
       and a.attname = 'type'
       and a.attnum > 0
       and not a.attisdropped;

    if actual_type is distinct from 'public.inspection_type'::regtype
       or actual_not_null is distinct from true
       or actual_identity is distinct from ''::"char"
       or actual_generated is distinct from ''::"char"
       or actual_default is distinct from '''REGULAR''::inspection_type' then
        raise exception
            'inspections.type semantics mismatch: type=%, not_null=%, identity=%, generated=%, default=%',
            actual_type::regtype, actual_not_null, actual_identity, actual_generated, actual_default;
    end if;
end
$$;

comment on column public.inspections.type is
    '점검 유형(REGULAR=정기, DETAILED=정밀, EMERGENCY=긴급)';
