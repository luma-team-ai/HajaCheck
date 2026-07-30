-- Flyway V35 — baseline 스탬프 누락 복구: menus / menu_role_access (#1306)
--
-- 왜 필요한가:
--   V1(baseline)에는 menu_node_type·menus·menu_role_access가 정의돼 있으나, prod(arm1 전용
--   postgres, DB hajacheck)는 2026-07-22 baseline-on-migrate 로 V1을 **실행하지 않고 스탬프만**
--   했다. 그 시점의 실스키마에 이 3개 객체가 없었고 V1↔실스키마 diff에서 누락돼, Flyway 이력상
--   "적용됨"인데 실물은 없는 상태로 남았다.
--   #1003(사이드바·관리자 nav를 DB 조회로 전환)이 이 엔티티를 실제로 사용하기 시작하면서
--   ddl-auto=validate 가 'missing table [menu_role_access]' 로 기동을 거부해 승격 배포가 실패했다.
--
-- 설계:
--   ① 전부 IF NOT EXISTS/가드 — V1을 실제로 실행한 DB(빈 DB·팀 로컬·공유 dev)에서는 완전 no-op.
--   ② DDL은 V1 정의를 그대로 옮긴다(제약·트리거·인덱스 포함) — 두 경로의 최종 스키마 동일 보장.
--   ③ 메뉴 트리 시드 포함: menus 는 마이그레이션 시드가 없어 공유 dev DB에만 수동 입력돼 있었다.
--      테이블만 만들면 prod nav 가 빈 상태가 되므로 공유 dev 기준 29행 + 접근권한 61행을 함께 넣는다.
--      created_by/updated_by 는 전부 NULL(환경별 users id 의존 제거).
--   ④ 자기참조 FK(parent_id) 삽입 순서 의존을 없애기 위해 parent_id 는 NULL 로 넣고 이후 UPDATE 로 연결.

-- (1) enum
do $$
begin
    if not exists (
        select 1 from pg_type t join pg_namespace n on n.oid = t.typnamespace
         where n.nspname = 'public' and t.typname = 'menu_node_type'
    ) then
        create type public.menu_node_type as enum ('GROUP', 'INTERNAL', 'EXTERNAL');
    end if;
end
$$;

-- (2) menus
create table if not exists menus
(
    id                  bigint generated always as identity
        primary key,
    code                varchar(100)                            not null
        unique,
    name                varchar(100)                            not null,
    menu_type           menu_node_type                          not null,
    parent_id           bigint
        constraint fk_menus_parent
            references menus
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
        references users,
    updated_by          bigint
        references users,
    created_at          timestamp with time zone default now() not null,
    updated_at          timestamp with time zone default now() not null,
    constraint ck_menus_not_self_parent
        check ((parent_id IS NULL) OR (parent_id <> id)),
    constraint ck_menus_sort_order_nonnegative
        check (sort_order >= 0),
    constraint ck_menus_icon_single
        check (
            (menu_type = 'GROUP'::menu_node_type AND num_nonnulls(icon_key, icon_url) <= 1)
            OR (menu_type <> 'GROUP'::menu_node_type AND num_nonnulls(icon_key, icon_url) = 1)
        ),
    constraint ck_menus_path_by_type
        check (
            (menu_type = 'GROUP'::menu_node_type AND path IS NULL)
            OR (menu_type <> 'GROUP'::menu_node_type AND path IS NOT NULL)
        )
);

comment on table menus is '사이드바 및 관리자 메뉴 트리를 관리한다.';

-- (3) menu_role_access
create table if not exists menu_role_access
(
    menu_id    bigint                                 not null
        references menus
            on delete cascade,
    role       role_type                              not null,
    created_by bigint
        references users,
    created_at timestamp with time zone default now() not null,
    primary key (menu_id, role)
);

create index if not exists idx_menu_role_access_role
    on menu_role_access (role, menu_id);

-- (4) 트리거 — V1 정의와 동일. set_updated_at() 은 V1/실스키마에 이미 존재한다.
create or replace function check_menu_role_access_not_group() returns trigger
    language plpgsql
as
$fn$
declare
    target_menu_type menu_node_type;
begin
    select menu_type into target_menu_type
    from menus
    where id = new.menu_id;

    if target_menu_type = 'GROUP'::menu_node_type then
        raise exception
            'menu_role_access.menu_id % refers to a GROUP menu; GROUP menus must not have direct menu_role_access rows',
            new.menu_id;
    end if;
    return new;
end;
$fn$;

do $$
begin
    if not exists (
        select 1 from pg_trigger
         where tgrelid = 'public.menus'::regclass
           and tgname = 'trg_menus_set_updated_at'
           and not tgisinternal
    ) then
        create trigger trg_menus_set_updated_at
            before update on menus
            for each row execute procedure set_updated_at();
    end if;

    if not exists (
        select 1 from pg_trigger
         where tgrelid = 'public.menu_role_access'::regclass
           and tgname = 'trg_menu_role_access_reject_group'
           and not tgisinternal
    ) then
        create trigger trg_menu_role_access_reject_group
            before insert or update of menu_id on menu_role_access
            for each row execute procedure check_menu_role_access_not_group();
    end if;
end
$$;

-- (5) 메뉴 트리 시드 (멱등 — code / (menu_id, role) 충돌 시 무시)
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (51, 'SUPPORT_AI_ASSISTANT', 'AI 어시스턴트', 'INTERNAL', NULL, '/support/ai-assistant', NULL, 'support', NULL, 10, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (30, 'DASHBOARD', '대시보드', 'GROUP', NULL, NULL, NULL, 'dashboard', NULL, 10, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (31, 'FACILITIES', '시설물 관리', 'GROUP', NULL, NULL, NULL, 'facilities', NULL, 20, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (32, 'INSPECTIONS', '점검 관리', 'GROUP', NULL, NULL, NULL, 'inspections', NULL, 30, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (33, 'REPORTS', '보고서', 'GROUP', NULL, NULL, NULL, 'reports', NULL, 50, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (34, 'SUPPORT', '고객지원', 'GROUP', NULL, NULL, NULL, 'support', NULL, 60, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (35, 'MYPAGE', '마이페이지', 'GROUP', NULL, NULL, NULL, 'mypage', NULL, 70, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (36, 'ADMIN_CONSOLE', '관리자 페이지', 'GROUP', NULL, NULL, NULL, 'admin', NULL, 90, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (37, 'DEFECTS', '하자 관리', 'INTERNAL', NULL, '/defects/list', NULL, 'defects', NULL, 40, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (38, 'STATISTICS', '통계', 'INTERNAL', NULL, '/statistics', NULL, 'statistics', NULL, 80, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (39, 'DASHBOARD_OVERVIEW', '전체 시설물 현황', 'INTERNAL', NULL, '/dashboard', NULL, 'dashboard', NULL, 10, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (40, 'DASHBOARD_UPCOMING_INSPECTIONS', '다음 점검일 도래', 'INTERNAL', NULL, '/dashboard/upcoming-inspections', NULL, 'dashboard', NULL, 20, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (42, 'FACILITIES_LIST', '시설물 목록/등록', 'INTERNAL', NULL, '/facilities/list', NULL, 'facilities', NULL, 10, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (44, 'FACILITIES_MAP', '지도 뷰', 'INTERNAL', NULL, '/facilities/map', NULL, 'facilities', NULL, 30, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (45, 'INSPECTIONS_CREATE', '점검(회차) 생성', 'INTERNAL', NULL, '/inspections/create', NULL, 'inspections', NULL, 10, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (46, 'INSPECTIONS_AI_ANALYSIS', 'AI 분석 실행/상태', 'INTERNAL', NULL, '/inspections/ai-analysis', NULL, 'inspections', NULL, 20, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (47, 'INSPECTIONS_RESULT_VIEWER', '분석 결과 뷰어', 'INTERNAL', NULL, '/inspections/1/viewer', NULL, 'inspections', NULL, 30, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (54, 'MYPAGE_PROFILE', '내 정보', 'INTERNAL', NULL, '/mypage/profile', NULL, 'mypage', NULL, 10, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (55, 'MYPAGE_INSPECTIONS', '내 점검 이력/보고서', 'INTERNAL', NULL, '/mypage/inspections', NULL, 'mypage', NULL, 20, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (56, 'MYPAGE_PLAN', '내 플랜', 'INTERNAL', NULL, '/mypage/plan', NULL, 'mypage', NULL, 30, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (57, 'ADMIN_USERS', '사용자 관리', 'INTERNAL', NULL, '/admin/users', NULL, 'admin', NULL, 10, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (58, 'ADMIN_PLANS_QUOTA', '플랜·쿼터 관리', 'INTERNAL', NULL, '/admin/plans-quota', NULL, 'admin', NULL, 20, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (48, 'REPORTS_LIST', '보고서 목록/이력 관리', 'INTERNAL', NULL, '/reports/list', NULL, 'reports', NULL, 10, true, false, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (49, 'REPORTS_EDITOR', '보고서 편집·미리보기', 'INTERNAL', NULL, '/reports/editor', NULL, 'reports', NULL, 20, true, false, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (50, 'REPORTS_EXPORT_PDF', 'PDF 내보내기', 'INTERNAL', NULL, '/reports/export-pdf', NULL, 'reports', NULL, 30, true, false, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-25 08:34:23.522091+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (59, 'INSPECTIONS_REPORT_ENTRANCE', '점검 요약 및 보고서 생성', 'INTERNAL', NULL, '/inspections/report-entrance', NULL, 'inspections', NULL, 40, true, true, false, NULL, NULL, NULL, '2026-07-25 14:17:51.541641+00', '2026-07-27 05:54:41.919325+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (52, 'SUPPORT_CHATBOT', '상담 챗봇', 'INTERNAL', NULL, '/support/chat-bot', NULL, 'support', NULL, 20, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-28 01:44:22.094144+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (53, 'SUPPORT_HISTORY', '내 상담 이력', 'INTERNAL', NULL, '/support/history', NULL, 'support', NULL, 30, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-28 01:44:22.106188+00') on conflict (code) do nothing;
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern, icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab, description, created_by, updated_by, created_at, updated_at) overriding system value values (43, 'FACILITIES_INSPECTION_CYCLE', '점검 주기 설정', 'INTERNAL', NULL, '/facilities/inspection-cycle', NULL, 'facilities', NULL, 20, true, true, false, NULL, NULL, NULL, '2026-07-25 08:34:23.522091+00', '2026-07-29 06:02:33.319194+00') on conflict (code) do nothing;

-- 부모 연결(자기참조 FK 순서 의존 제거)
update menus set parent_id = 34 where id = 51 and parent_id is distinct from 34;
update menus set parent_id = 30 where id = 39 and parent_id is distinct from 30;
update menus set parent_id = 30 where id = 40 and parent_id is distinct from 30;
update menus set parent_id = 31 where id = 42 and parent_id is distinct from 31;
update menus set parent_id = 31 where id = 44 and parent_id is distinct from 31;
update menus set parent_id = 32 where id = 45 and parent_id is distinct from 32;
update menus set parent_id = 32 where id = 46 and parent_id is distinct from 32;
update menus set parent_id = 32 where id = 47 and parent_id is distinct from 32;
update menus set parent_id = 35 where id = 54 and parent_id is distinct from 35;
update menus set parent_id = 35 where id = 55 and parent_id is distinct from 35;
update menus set parent_id = 35 where id = 56 and parent_id is distinct from 35;
update menus set parent_id = 36 where id = 57 and parent_id is distinct from 36;
update menus set parent_id = 36 where id = 58 and parent_id is distinct from 36;
update menus set parent_id = 33 where id = 48 and parent_id is distinct from 33;
update menus set parent_id = 33 where id = 49 and parent_id is distinct from 33;
update menus set parent_id = 33 where id = 50 and parent_id is distinct from 33;
update menus set parent_id = 32 where id = 59 and parent_id is distinct from 32;
update menus set parent_id = 34 where id = 52 and parent_id is distinct from 34;
update menus set parent_id = 34 where id = 53 and parent_id is distinct from 34;
update menus set parent_id = 32 where id = 43 and parent_id is distinct from 32;

insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (39, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (39, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (39, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (40, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (40, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (40, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (42, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (42, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (42, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (43, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (43, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (43, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (44, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (44, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (44, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (45, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (45, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (45, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (46, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (46, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (47, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (47, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (37, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (37, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (37, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (48, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (48, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (48, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (49, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (49, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (50, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (50, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (50, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (51, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (51, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (51, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (52, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (52, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (52, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (53, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (53, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (53, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (54, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (54, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (54, 'COUNSELOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (54, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (55, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (55, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (55, 'COUNSELOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (55, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (56, 'USER', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (56, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (56, 'COUNSELOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (56, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (38, 'INSPECTOR', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (38, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (57, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (58, 'ADMIN', NULL, '2026-07-25 08:34:23.522091+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (59, 'ADMIN', NULL, '2026-07-27 05:50:01.679501+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (59, 'INSPECTOR', NULL, '2026-07-27 05:50:01.679501+00') on conflict (menu_id, role) do nothing;
insert into menu_role_access (menu_id, role, created_by, created_at) VALUES (59, 'USER', NULL, '2026-07-27 05:50:01.679501+00') on conflict (menu_id, role) do nothing;

-- (6) identity 시퀀스를 시드 최대 id 뒤로 옮긴다(수동 id 지정분과 충돌 방지)
do $$
declare
    next_id bigint;
begin
    select coalesce(max(id), 0) + 1 into next_id from menus;
    execute format('alter table menus alter column id restart with %s', next_id);
end
$$;
