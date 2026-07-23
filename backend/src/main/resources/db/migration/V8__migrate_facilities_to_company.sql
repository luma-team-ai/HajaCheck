-- 시설 소유 범위를 사용자(owner_id)에서 회사(company_id)로 전환한다.
-- 빈 DB의 V1 스키마와 이미 최신 캐노니컬 DDL이 적용된 baseline-on-existing DB를 모두 지원한다.

do $$
declare
    has_owner_id boolean;
    has_company_id boolean;
    invalid_facility_id bigint;
    invalid_owner_id bigint;
begin
    if to_regclass('public.facilities') is null
            or to_regclass('public.users') is null
            or to_regclass('public.companies') is null then
        raise exception
            'facilities company migration requires public.facilities, public.users, and public.companies';
    end if;

    select exists (
        select 1
          from pg_attribute
         where attrelid = 'public.facilities'::regclass
           and attname = 'owner_id'
           and not attisdropped
    ) into has_owner_id;

    select exists (
        select 1
          from pg_attribute
         where attrelid = 'public.facilities'::regclass
           and attname = 'company_id'
           and not attisdropped
    ) into has_company_id;

    if has_owner_id and has_company_id then
        raise exception
            'facilities ownership schema is ambiguous: owner_id and company_id both exist';
    end if;

    if not has_owner_id and not has_company_id then
        raise exception
            'facilities ownership column is missing: expected owner_id or company_id';
    end if;

    if has_owner_id then
        if not exists (
            select 1
              from pg_attribute
             where attrelid = 'public.facilities'::regclass
               and attname = 'owner_id'
               and atttypid = 'pg_catalog.int8'::regtype
               and attnotnull
               and not attisdropped
        ) then
            raise exception
                'facilities.owner_id must be bigint not null before company migration';
        end if;

        if not exists (
            select 1
              from pg_attribute
             where attrelid = 'public.users'::regclass
               and attname = 'company_id'
               and atttypid = 'pg_catalog.int8'::regtype
               and not attisdropped
        ) then
            raise exception
                'users.company_id bigint column is required for facilities ownership migration';
        end if;

        select f.id, f.owner_id
          into invalid_facility_id, invalid_owner_id
          from public.facilities f
          left join public.users u on u.id = f.owner_id
          left join public.companies c on c.id = u.company_id
         where u.id is null
            or u.company_id is null
            or c.id is null
         order by f.id
         limit 1;

        if invalid_facility_id is not null then
            raise exception
                'cannot map facilities.owner_id to company_id: facility_id=%, owner_id=% has no valid company',
                invalid_facility_id, invalid_owner_id;
        end if;

        alter table public.facilities
            add column company_id bigint;

        update public.facilities f
           set company_id = u.company_id
          from public.users u
         where u.id = f.owner_id;

        alter table public.facilities
            alter column company_id set not null;

        -- owner_id를 제거하면 해당 컬럼의 기존 FK와 인덱스도 함께 제거된다.
        alter table public.facilities
            drop column owner_id;

        alter table public.facilities
            add constraint fk_facilities_company
                foreign key (company_id) references public.companies (id);

        create index idx_facilities_company
            on public.facilities (company_id);

        comment on column public.facilities.company_id is '시설을 소유·관리하는 회사 식별자';
    end if;
end
$$;

-- 변환 경로와 baseline-on-existing no-op 경로가 같은 최종 계약인지 검증한다.
do $$
declare
    company_attnum smallint;
    company_fk_count integer;
    company_index_count integer;
begin
    select attnum
      into company_attnum
      from pg_attribute
     where attrelid = 'public.facilities'::regclass
       and attname = 'company_id'
       and atttypid = 'pg_catalog.int8'::regtype
       and attnotnull
       and not attisdropped;

    if company_attnum is null then
        raise exception
            'facilities.company_id must be bigint not null after company migration';
    end if;

    if exists (
        select 1
          from pg_attribute
         where attrelid = 'public.facilities'::regclass
           and attname = 'owner_id'
           and not attisdropped
    ) then
        raise exception
            'facilities.owner_id must not exist after company migration';
    end if;

    select count(*)
      into company_fk_count
      from pg_constraint c
      join pg_attribute company_id_column
        on company_id_column.attrelid = 'public.companies'::regclass
       and company_id_column.attname = 'id'
       and not company_id_column.attisdropped
     where c.conrelid = 'public.facilities'::regclass
       and c.contype = 'f'
       and c.convalidated
       and c.confrelid = 'public.companies'::regclass
       and c.confupdtype = 'a'
       and c.confdeltype = 'a'
       and cardinality(c.conkey) = 1
       and c.conkey[1] = company_attnum
       and cardinality(c.confkey) = 1
       and c.confkey[1] = company_id_column.attnum;

    if company_fk_count <> 1 then
        raise exception
            'facilities.company_id foreign key semantics mismatch: expected exactly one validated companies(id) FK';
    end if;

    if exists (
        select 1
          from pg_constraint c
         where c.conrelid = 'public.facilities'::regclass
           and c.contype = 'f'
           and company_attnum = any (c.conkey)
           and not (
               c.convalidated
               and c.confrelid = 'public.companies'::regclass
               and c.confupdtype = 'a'
               and c.confdeltype = 'a'
               and cardinality(c.conkey) = 1
               and cardinality(c.confkey) = 1
           )
    ) then
        raise exception
            'facilities.company_id has an unexpected foreign key';
    end if;

    select count(*)
      into company_index_count
      from pg_index i
      join pg_class index_relation on index_relation.oid = i.indexrelid
      join pg_am access_method on access_method.oid = index_relation.relam
     where i.indrelid = 'public.facilities'::regclass
       and index_relation.relname = 'idx_facilities_company'
       and access_method.amname = 'btree'
       and i.indisvalid
       and i.indisready
       and not i.indisunique
       and i.indpred is null
       and i.indexprs is null
       and i.indnkeyatts = 1
       and i.indnatts = 1
       and i.indkey[0] = company_attnum;

    if company_index_count <> 1 then
        raise exception
            'facilities.company_id index semantics mismatch: expected idx_facilities_company btree(company_id)';
    end if;
end
$$;
