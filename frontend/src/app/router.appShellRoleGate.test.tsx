// @vitest-environment jsdom
// #1513 — 기업회원 대시보드 셸(AppShellRoute)을 감싸는 ProtectedRoute에 allowedRoles가 실제로
// 배선돼 있는지 라우터 정의에서 직접 확인한다. 컴포넌트 단위 테스트(ProtectedRoute.test.tsx)는
// "가드가 주어지면 어떻게 동작하는가"만 고정하므로, 라우터가 가드를 안 걸어두면 아무도 못 잡는다
// (실제로 1차 작업분이 import만 추가하고 배선을 빠뜨린 상태였다).
import type { ReactElement } from 'react';
import type { RouteObject } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProtectedRoute } from '../shared/components/ProtectedRoute';
import { COMPANY_DASHBOARD_ROLES } from '../shared/constants/roles';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

// router.tsx는 lazy import가 많아 임포트 자체가 무겁다(router.dev-routes.test.tsx와 동일 사유).
const IMPORT_TIMEOUT_MS = 15_000;

/** children 중 지정 path를 가진 라우트가 있는 부모 라우트를 찾는다. */
function findParentOf(routes: RouteObject[], childPath: string): RouteObject | undefined {
  for (const route of routes) {
    if (route.children?.some((child) => child.path === childPath)) {
      return route;
    }
    if (route.children) {
      const found = findParentOf(route.children, childPath);
      if (found) {
        return found;
      }
    }
  }
  return undefined;
}

describe('기업 대시보드 셸 role 가드(#1513)', () => {
  it(
    '/dashboard의 부모 라우트가 ProtectedRoute + allowedRoles=COMPANY_DASHBOARD_ROLES로 감싸져 있다',
    async () => {
      vi.stubEnv('DEV', false);
      const { router } = await import('./router');

      const shellRoute = findParentOf(router.routes, '/dashboard');
      expect(shellRoute).toBeDefined();

      const element = shellRoute?.element as ReactElement<{ allowedRoles?: readonly string[] }>;
      expect(element.type).toBe(ProtectedRoute);
      expect(element.props.allowedRoles).toEqual(COMPANY_DASHBOARD_ROLES);

      router.dispose();
    },
    IMPORT_TIMEOUT_MS,
  );
});
