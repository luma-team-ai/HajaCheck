-- 메뉴 스키마 expand migration: 기존 v0.3 계열 DB에 메뉴 트리와 역할별 노출 매핑을 추가한다.
-- 신규 테이블만 생성하므로 데이터 백필은 없으며, 같은 파일을 재실행해도 안전해야 한다.

-- Run this file with psql autocommit enabled and ON_ERROR_STOP=1.
select pg_advisory_lock(hashtext('hajacheck:menu-schema:migration'));

do $migration$
declare
    actual_role_labels text[];
begin
    if to_regclass('public.users') is null then
        raise exception 'menu schema migration requires public.users';
    end if;

    if to_regtype('public.role_type') is null then
        raise exception 'menu schema migration requires public.role_type';
    end if;

    if to_regprocedure('public.set_updated_at()') is null then
        raise exception 'menu schema migration requires public.set_updated_at()';
    end if;

    select array_agg(enum_meta.enumlabel::text order by enum_meta.enumsortorder)
    into actual_role_labels
    from pg_enum enum_meta
    where enum_meta.enumtypid = to_regtype('public.role_type');

    if actual_role_labels is distinct from array['ADMIN', 'INSPECTOR', 'USER', 'COUNSELOR'] then
        raise exception 'public.role_type labels do not match the menu schema prerequisite: %',
            actual_role_labels;
    end if;
end
$migration$;

do $migration$
declare
    actual_menu_labels text[];
begin
    if to_regtype('public.menu_node_type') is null then
        create type public.menu_node_type as enum ('GROUP', 'INTERNAL', 'EXTERNAL');
    else
        select array_agg(enum_meta.enumlabel::text order by enum_meta.enumsortorder)
        into actual_menu_labels
        from pg_enum enum_meta
        where enum_meta.enumtypid = to_regtype('public.menu_node_type');

        if actual_menu_labels is distinct from array['GROUP', 'INTERNAL', 'EXTERNAL'] then
            raise exception 'existing public.menu_node_type labels do not match the canonical DDL: %',
                actual_menu_labels;
        end if;
    end if;
end
$migration$;

comment on type public.menu_node_type is '사이드바 메뉴 노드 유형(그룹/내부 링크/외부 링크)';

create table if not exists public.menus
(
    id                  bigint generated always as identity
        primary key,
    code                varchar(100)                            not null
        unique,
    name                varchar(100)                            not null,
    menu_type           public.menu_node_type                   not null,
    parent_id           bigint
        constraint fk_menus_parent
            references public.menus
            on delete restrict,
    path                varchar(500),
    active_path_pattern varchar(500),
    icon_key            varchar(100),
    icon_url            varchar(500),
    sort_order          integer                  default 0     not null,
    is_visible          boolean                  default true  not null,
    is_enabled          boolean                  default true  not null,
    opens_new_tab       boolean                  default false not null,
    description         varchar(500),
    created_by          bigint
        references public.users,
    updated_by          bigint
        references public.users,
    created_at          timestamp with time zone default now() not null,
    updated_at          timestamp with time zone default now() not null,
    constraint ck_menus_not_self_parent
        check ((parent_id is null) or (parent_id <> id)),
    constraint ck_menus_sort_order_nonnegative
        check (sort_order >= 0),
    constraint ck_menus_icon_single
        check (
            (menu_type = 'GROUP'::public.menu_node_type and num_nonnulls(icon_key, icon_url) <= 1)
            or (menu_type <> 'GROUP'::public.menu_node_type and num_nonnulls(icon_key, icon_url) = 1)
        ),
    constraint ck_menus_path_by_type
        check (
            (menu_type = 'GROUP'::public.menu_node_type and path is null)
            or (menu_type <> 'GROUP'::public.menu_node_type and path is not null)
        )
);

comment on table public.menus is '사이드바 및 관리자 메뉴 트리를 관리한다. lock_version을 두지 않는다 — 소수 관리자가 드물게 편집하는 설정 테이블이라 동시 갱신 충돌 위험이 낮다. 필요해지면 companies/reports처럼 후속으로 추가한다.';
comment on column public.menus.id is '메뉴 식별자';
comment on column public.menus.code is '변경되지 않는 고유 메뉴 코드(예: DASHBOARD, ADMIN_USERS)';
comment on column public.menus.name is '표시 메뉴명';
comment on column public.menus.menu_type is '메뉴 노드 유형(그룹/내부 링크/외부 링크)';
comment on column public.menus.parent_id is '상위 메뉴 식별자. 자기참조이며 하위 메뉴가 있는 상위 메뉴는 삭제할 수 없다(ON DELETE RESTRICT)';
comment on column public.menus.path is '이동 경로. GROUP은 NULL, INTERNAL/EXTERNAL은 필수. 같은 라우트를 가리키는 여러 메뉴 항목을 허용하므로 UNIQUE로 두지 않는다';
comment on column public.menus.active_path_pattern is '실제 라우트가 path와 다를 때(예: 메뉴 path=/facilities/list, 실제 라우트=/facilities, 상세 라우트=/defects/:id) 활성 메뉴 판정에 쓰는 동적 경로 패턴';
comment on column public.menus.icon_key is '프론트 번들 아이콘 키(예: dashboard, facilities). 현재 프론트가 SVG를 번들 import하는 방식이라 icon_url보다 우선 사용한다';
comment on column public.menus.icon_url is 'CDN 아이콘을 쓸 때만 채우는 URL. icon_key와 동시에 채우지 않는다';
comment on column public.menus.sort_order is '동일 부모 하위 노출 순서. 정렬은 sort_order, id 순';
comment on column public.menus.is_visible is '메뉴 표시 여부';
comment on column public.menus.is_enabled is '클릭 가능 여부(아직 미구현된 메뉴 등을 표시는 하되 비활성화할 때 사용)';
comment on column public.menus.opens_new_tab is '외부 링크를 새 창으로 여는지 여부';
comment on column public.menus.description is '관리자용 메뉴 설명';
comment on column public.menus.created_by is '메뉴를 생성한 관리자 사용자 식별자. 초기 시드 데이터는 NULL 허용';
comment on column public.menus.updated_by is '메뉴를 마지막으로 수정한 관리자 사용자 식별자';
comment on column public.menus.created_at is '메뉴 생성 시각';
comment on column public.menus.updated_at is '메뉴 최종 수정 시각';

create index if not exists idx_menus_parent
    on public.menus (parent_id);

create table if not exists public.menu_role_access
(
    menu_id    bigint                                 not null
        references public.menus
            on delete cascade,
    role       public.role_type                       not null,
    created_by bigint
        references public.users,
    created_at timestamp with time zone default now() not null,
    primary key (menu_id, role)
);

comment on table public.menu_role_access is '역할별로 노출되는 메뉴를 관리한다. 매핑 행이 존재하면 해당 역할에 노출되는 방식이라 can_view 컬럼은 두지 않는다. GROUP 메뉴에는 매핑 행을 넣지 않는다 — 허용된 자식이 하나라도 있으면 부모 GROUP은 서비스 로직이 자동으로 포함시킨다';
comment on column public.menu_role_access.menu_id is '메뉴 식별자. 메뉴 삭제 시 매핑도 함께 삭제된다(ON DELETE CASCADE)';
comment on column public.menu_role_access.role is '이 메뉴에 접근 가능한 역할';
comment on column public.menu_role_access.created_by is '매핑을 등록한 관리자 사용자 식별자';
comment on column public.menu_role_access.created_at is '매핑 등록 시각';

create index if not exists idx_menu_role_access_role
    on public.menu_role_access (role, menu_id);

do $migration$
begin
    if not exists (
        select 1
        from pg_trigger trigger_meta
        where trigger_meta.tgname = 'trg_menus_set_updated_at'
          and trigger_meta.tgrelid = 'public.menus'::regclass
          and not trigger_meta.tgisinternal
    ) then
        create trigger trg_menus_set_updated_at
            before update on public.menus
            for each row execute procedure public.set_updated_at();
    end if;
end
$migration$;

comment on trigger trg_menus_set_updated_at on public.menus is 'menus 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

select pg_advisory_unlock(hashtext('hajacheck:menu-schema:migration'));
