// @vitest-environment jsdom
import type { ReactElement } from 'react';
import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

async function loadRouter(isDev: boolean) {
  vi.stubEnv('DEV', isDev);
  vi.resetModules();
  return (await import('./router')).router;
}

// AppShellRoute 등 여러 겹으로 중첩된 children 트리 어디에 있든 path로 라우트를 찾는다
// (router.routes는 최상위 배열만 보므로, /facilities처럼 중첩된 라우트는 재귀 탐색이 필요하다).
function findRouteByPath(routes: RouteObject[], path: string): RouteObject | undefined {
  for (const route of routes) {
    if (route.path === path) {
      return route;
    }
    if (route.children) {
      const found = findRouteByPath(route.children, path);
      if (found) {
        return found;
      }
    }
  }
  return undefined;
}

// router.tsx의 라우트(따라서 lazy import) 수가 늘어날수록 vi.resetModules() 이후 './router'를
// 새로 임포트하는 비용이 커진다 — 기본 5000ms 타임아웃이 이미 라우트 40여 개 시점에 ~4s로
// 바짝 붙어 있었고, AI 분석 현황 모니터링(신규) 라우트 추가로 실제로 넘겼다(로컬 재현). 라우트
// 수가 늘어날수록 자연히 느려지는 임포트 비용 테스트라 넉넉한 타임아웃으로 여유를 둔다.
const IMPORT_TIMEOUT_MS = 15_000;

describe('개발 전용 라우트', () => {
  it(
    '개발 환경에는 차트 쇼케이스를 등록한다',
    async () => {
      const router = await loadRouter(true);

      expect(router.routes.some((route) => route.path === '/dev/charts')).toBe(true);
      router.dispose();
    },
    IMPORT_TIMEOUT_MS,
  );

  it(
    '운영 환경에는 차트 쇼케이스를 등록하지 않는다',
    async () => {
      const router = await loadRouter(false);

      expect(router.routes.some((route) => route.path === '/dev/charts')).toBe(false);
      router.dispose();
    },
    IMPORT_TIMEOUT_MS,
  );
});

// #1361 회귀고정 — 하위 경로 없는 '/facilities'는 매칭되는 라우트가 없어 주소창에 직접
// 입력하면 React Router 기본 404 에러 페이지가 떴다. /facilities/list로 리다이렉트해야 한다.
describe("'/facilities' 하위 경로 없는 진입", () => {
  it(
    "'/facilities/list'로 리다이렉트하는 라우트가 등록돼 있다(#1361)",
    async () => {
      const router = await loadRouter(false);

      const route = findRouteByPath(router.routes, '/facilities');
      expect(route).toBeDefined();
      const element = route?.element as ReactElement<{ to: string; replace?: boolean }>;
      expect(element.type).toBe(Navigate);
      expect(element.props.to).toBe('/facilities/list');
      expect(element.props.replace).toBe(true);

      router.dispose();
    },
    IMPORT_TIMEOUT_MS,
  );
});
