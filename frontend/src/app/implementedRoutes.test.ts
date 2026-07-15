import { describe, expect, it } from 'vitest';
import { isRouteImplemented } from './implementedRoutes';

// 정확한 경로 화이트리스트 회귀 방지(#227 리뷰 P1) — 이전에는 '/defects/:id' 동적 패턴을 써서
// SideNavBar의 미구현 플레이스홀더 href(예: '/defects/list')까지 구현된 것으로 잘못 통과시켰다.
describe('isRouteImplemented', () => {
  it.each(['/dashboard', '/defects/detail', '/mypage/plan'])(
    '실제 라우터에 연결된 정확한 경로 %s는 true를 반환한다',
    (href) => {
      expect(isRouteImplemented(href)).toBe(true);
    },
  );

  it.each(['/defects/list', '/facilities/list', '/statistics', '/settings', '/my-page/profile'])(
    '아직 라우터에 없는 경로 %s는 false를 반환한다',
    (href) => {
      expect(isRouteImplemented(href)).toBe(false);
    },
  );

  it("'/defects/:id' 같은 동적 세그먼트로 우연히 매치되던 플레이스홀더는 더 이상 통과하지 않는다", () => {
    expect(isRouteImplemented('/defects/list')).toBe(false);
  });
});
