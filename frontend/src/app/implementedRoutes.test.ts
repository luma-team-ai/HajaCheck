import { describe, expect, it } from 'vitest';
import { isRouteImplemented } from './implementedRoutes';

// 정확한 경로 화이트리스트 회귀 방지(#227 리뷰 P1) — 이전에는 '/defects/:id' 동적 패턴을 써서
// SideNavBar의 미구현 플레이스홀더 href(예: '/facilities/list')까지 구현된 것으로 잘못 통과시켰다.
// '/defects/list'는 HAJA-30에서 실제 정적 라우트로 구현되어 true 목록으로 이동했다.
describe('isRouteImplemented', () => {
  it.each([
    '/dashboard',
    '/statistics', // HAJA-40(#27) — 통계 대시보드 구현으로 화이트리스트에 추가됨(원래 미구현 목록에 있었음)
    '/dashboard/ai-weekly-briefing',
    '/dashboard/upcoming-inspections',
    '/defects/list',
    '/mypage/plan',
    '/mypage/profile',
    '/mypage/inspections',
    '/reports',
    '/facilities/list',
    '/admin/plans-quota',
  ])(
    '실제 라우터에 연결된 정확한 경로 %s는 true를 반환한다',
    (href) => {
      expect(isRouteImplemented(href)).toBe(true);
    },
  );

  it.each(['/settings'])(
    '아직 라우터에 없는 경로 %s는 false를 반환한다',
    (href) => {
      expect(isRouteImplemented(href)).toBe(false);
    },
  );

  it("'/defects/:id' 같은 동적 세그먼트로 우연히 매치되던 플레이스홀더(예: 임의 id)는 통과하지 않는다", () => {
    expect(isRouteImplemented('/defects/999')).toBe(false);
  });

  // 점검 id 스코프 라우트 회귀 방지(PR #938 리뷰 P1) — SideNavBar가 activeInspectionId로
  // '/inspections/{id}/analysis|viewer|reports'를 동적 생성하는데, id는 실행 시점 값이라
  // 정확한 문자열 화이트리스트로는 커버할 수 없었다. 숫자 id로 한정한 별도 패턴을 추가했다.
  describe('점검 id 스코프 라우트(/inspections/:id/analysis|viewer|reports)', () => {
    it.each([
      '/inspections/1/viewer',
      '/inspections/42/analysis',
      '/inspections/42/viewer',
      '/inspections/42/reports',
    ])('%s는 true를 반환한다', (href) => {
      expect(isRouteImplemented(href)).toBe(true);
    });

    it.each([
      '/inspections/42/reports/generate', // 진입점이 아닌 편집 화면 — 화이트리스트 대상 아님
      '/inspections/abc/analysis', // 숫자가 아닌 id는 매치하지 않는다(#227 재발 방지)
      '/inspections/42/unknown',
    ])('%s는 false를 반환한다', (href) => {
      expect(isRouteImplemented(href)).toBe(false);
    });
  });
});
