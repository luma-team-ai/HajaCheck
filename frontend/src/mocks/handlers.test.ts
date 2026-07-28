import { afterEach, describe, expect, it, vi } from 'vitest';

describe('MSW handler exports', () => {
  afterEach(() => {
    vi.resetModules();
    vi.unstubAllEnvs();
  });

  // 이 파일이 집계하는 feature handler 수가 늘어날수록(신규 feature 추가마다) 동적 import 비용이
  // 커진다 — 전체 스위트 병렬 실행 시 기본 5000ms 타임아웃에 종종 걸린다(router.dev-routes.test.tsx와
  // 동일한 이유). 넉넉한 타임아웃으로 여유를 둔다.
  it(
    'hybrid 런타임은 브라우저용 handlers만 비우고 테스트용 전체 집합은 유지한다',
    async () => {
      vi.stubEnv('VITE_ENABLE_MSW', 'hybrid');

      const { allMockHandlers, handlers } = await import('./handlers');

      expect(handlers).toEqual([]);
      expect(allMockHandlers.length).toBeGreaterThan(0);
    },
    15_000,
  );

  it(
    '순수 목 런타임은 시설물 핸들러를 전역 handlers에 포함한다',
    async () => {
      vi.stubEnv('VITE_ENABLE_MSW', 'true');

      const { handlers } = await import('./handlers');
      const { facilityHandlers } = await import('../features/facility/api/facilityApi.handlers');

      expect(handlers).toEqual(expect.arrayContaining(facilityHandlers));
    },
    15_000,
  );
});
