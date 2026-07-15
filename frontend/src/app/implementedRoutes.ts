// SideNavBar 메뉴 클릭이 실제로 이동 가능한 라우트인지 판별한다(HAJA-186, #217 후속).
// SideNavBar 자체는 라우터 전체 구조를 몰라도 되도록, "어떤 href가 실제로 구현됐는지"는
// 라우터 쪽 지식으로 여기서만 관리하고 AppShellRoute → AppLayout → SideNavBar로 판별 함수를 주입한다.
//
// router.tsx의 AppShellRoute children에 새 라우트를 추가/제거하면 이 목록도 함께 갱신할 것.
import { matchPath } from 'react-router-dom';

const IMPLEMENTED_ROUTE_PATTERNS = ['/dashboard', '/defects/:id', '/mypage/plan'];

export function isRouteImplemented(href: string): boolean {
  return IMPLEMENTED_ROUTE_PATTERNS.some((pattern) => matchPath(pattern, href) !== null);
}
