-- Flyway V41 — 누락된 "AI 주간 브리핑 카드" 메뉴 복구 (#1522)
--
-- 배경 (레포 이력으로 확인한 사실만 적는다):
--   · #387(8ee53b84)은 menus/menu_role_access **스키마만** 만들었다. 실제 메뉴 행은 레포 밖
--     (운영 DB 직접 입력)에서 만들어져 시드 SQL이 레포에 없었다.
--   · #1003/#1026(930adfad, 2026-07-27)에서 사이드바를 DB 메뉴 조회 기반으로 전환하면서
--     **프론트 목(menu.mock.ts)에는 DASHBOARD_AI_WEEKLY_BRIEFING 을 넣었다.**
--   · #1308(V35)이 prod 덤프로 메뉴를 복구했을 때 대시보드 자식은 DASHBOARD_OVERVIEW(39)·
--     DASHBOARD_UPCOMING_INSPECTIONS(40) 둘뿐이었고, id 30~59 중 **41만 결번**이다
--     (V36 주석 실측 "menus 29행"과 일치).
--
--   즉 이 메뉴는 **레포 기준으로 DB에 존재한 적이 없고, 프론트 목에만 있었다.**
--   id 41 결번이 "삭제 흔적"인지 "애초에 안 만든 공백"인지는 레포만으로 판별할 수 없다 —
--   여기서는 그 판별에 기대지 않고, 41이 **현재 비어 있다는 사실**만 사용한다(아래 가드).
--
--   결과적으로 사이드바가 DB 기반으로 바뀐 시점부터 실서버에서 링크가 사라졌는데,
--   목에는 남아 있어 로컬에서는 계속 보였다 → 아무도 눈치채지 못했다
--   (통합테스트 HC_004_03 "해당 링크 메뉴 삭제되었음"에서 처음 드러남).
--
--   기능 자체는 살아 있다 — AiBriefingCard 위젯은 DashboardPage/UpcomingInspectionsPage에
--   인라인 렌더되고, 라우트 /dashboard/ai-weekly-briefing(앵커 스크롤)은 #478에서
--   **바로 이 사이드바 링크를 위해** 만든 것이다. 프론트(SideNavBar DEFAULT_ITEMS·
--   constants·implementedRoutes·menu.mock)는 이미 전부 배선돼 있어 DB 행만 채우면 된다.
--
--   PRD §4 IA 도 "AI 주간 브리핑 카드 (P1)"로 기술하므로 PRD가 정본이고 DB가 어긋난
--   상태다 → 문서가 아니라 DB를 맞춘다.
--
-- 규약: 형제 메뉴(39·40)와 동일하게 맞춘다.
--   ① menu_type=INTERNAL, icon_key='dashboard', is_visible/is_enabled=true, opens_new_tab=false
--   ② sort_order=30 (전체 시설물 현황 10 → 다음 점검일 도래 20 → 주간 브리핑 30)
--   ③ parent_id 는 V35와 같은 이유로 INSERT 시 NULL, 이후 UPDATE 로 연결
--      (자기참조 FK 삽입 순서 의존 제거)
--   ④ 역할 접근은 USER · INSPECTOR · ADMIN — 39/40과 동일
--
-- 멱등·안전성:
--   menus.id 는 generated always as identity 인데 V35가 explicit id 를 쓰면서 setval 을
--   하지 않았다. 그래서 "id 41 이 이미 다른 코드에 쓰이고 있을" 가능성을 배제할 수 없다고
--   보고, id·code 어느 쪽이든 선점돼 있으면 **삽입을 건너뛴다**(NOT EXISTS 가드).
--   이후 부모·역할 연결은 id 41 상수가 아니라 **code 로 조회**해 수행하므로,
--   삽입이 건너뛰어졌거나 id 가 다른 환경에서도 정확히 동작한다.

-- (1) 메뉴 행 — id 41 과 code 둘 다 비어 있을 때만 삽입
insert into menus (id, code, name, menu_type, parent_id, path, active_path_pattern,
                   icon_key, icon_url, sort_order, is_visible, is_enabled, opens_new_tab,
                   description, created_by, updated_by, created_at, updated_at)
overriding system value
select 41, 'DASHBOARD_AI_WEEKLY_BRIEFING', 'AI 주간 브리핑 카드', 'INTERNAL'::menu_node_type, NULL,
       '/dashboard/ai-weekly-briefing', NULL, 'dashboard', NULL, 30, true, true, false,
       NULL, NULL, NULL, now(), now()
where not exists (select 1 from menus where id = 41)
  and not exists (select 1 from menus where code = 'DASHBOARD_AI_WEEKLY_BRIEFING');

-- (2) 부모(대시보드 그룹) 연결 — code 로 조회해 id 상수 의존 제거
update menus
set parent_id = (select id from menus where code = 'DASHBOARD')
where code = 'DASHBOARD_AI_WEEKLY_BRIEFING'
  and parent_id is distinct from (select id from menus where code = 'DASHBOARD');

-- (3) 역할 접근 — 형제 메뉴와 동일한 USER/INSPECTOR/ADMIN
insert into menu_role_access (menu_id, role, created_by, created_at)
select m.id, r.role::role_type, NULL, now()
from menus m
         cross join (values ('USER'), ('INSPECTOR'), ('ADMIN')) as r(role)
where m.code = 'DASHBOARD_AI_WEEKLY_BRIEFING'
on conflict (menu_id, role) do nothing;
