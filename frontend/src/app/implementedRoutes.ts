// SideNavBar 메뉴 클릭이 실제로 이동 가능한 라우트인지 판별한다(HAJA-186, #217 후속).
// SideNavBar 자체는 라우터 전체 구조를 몰라도 되도록, "어떤 href가 실제로 구현됐는지"는
// 라우터 쪽 지식으로 여기서만 관리하고 AppShellRoute → AppLayout → SideNavBar로 판별 함수를 주입한다.
//
// router.tsx의 AppShellRoute children에 새 라우트를 추가/제거하면 이 목록도 함께 갱신할 것.
// 동적 파라미터 패턴(예: '/defects/:id')은 쓰지 않는다 — ':id' 자리가 임의의 한 세그먼트와
// 매치되어 SideNavBar의 미구현 플레이스홀더 href(예: '/defects/list')까지 구현된 것으로
// 잘못 통과시킨다(#227 리뷰 P1). 실제로 이동 가능한 정확한 경로만 화이트리스트한다.
const IMPLEMENTED_ROUTES = new Set([
  '/dashboard',
  '/dashboard/ai-weekly-briefing', // AiBriefingCard 인라인 위젯 앵커 스크롤(#478)
  '/dashboard/upcoming-inspections', // 다음 점검일 도래 독립 페이지(dev-03-02, #543)
  '/defects/list',
  '/mypage/plan',
  '/mypage/profile', // 마이페이지 내 정보(HAJA-361, #659)
  '/mypage/inspections', // 마이페이지 내 점검 이력 / 보고서(HAJA-366, #668)
  '/inspections/1/viewer',
  '/inspections/create',
  '/facilities/list',
  '/facilities/map',
  '/facilities/inspection-cycle',
  '/admin/users',
  '/admin/plans-quota',
  '/support/ai-assistant',
  // 플랫폼 관리자 콘솔(#535) — 7개 메뉴 placeholder. 여기 없으면 PlatformAdminShellRoute의
  // SideNavBar가 "아직 구현되지 않은 페이지입니다" 토스트를 띄우며 이동을 막는다.
  '/platform-admin/users',
  '/platform-admin/plans-quota',
  '/platform-admin/defect-types',
  '/platform-admin/counsels',
  '/platform-admin/rag-documents',
  '/platform-admin/stats',
  '/platform-admin/monitoring',
]);

export function isRouteImplemented(href: string): boolean {
  return IMPLEMENTED_ROUTES.has(href);
}
