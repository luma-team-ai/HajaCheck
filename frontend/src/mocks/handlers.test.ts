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

  // 비밀번호 변경(#1316, HAJA-602) 핸들러 하이브리드 제외 — 보안 리뷰 P2-3. BE #1315가 병렬로 실
  // 구현 중이므로, hybrid에서 이 엔드포인트만 MSW가 가로채지 않아야 한다(안 그러면 실 백엔드가 붙은
  // dev에서도 PATCH가 목으로 가로채져 "비밀번호가 안 바뀌었는데 200 성공"으로 보인다). 다른 mypage
  // 엔드포인트(플랜/좌석 등)는 아직 병렬 실구현이 없어 hybrid에서도 그대로 목이 유지돼야 한다.
  it(
    'hybrid 런타임은 비밀번호 변경 핸들러만 제외하고 나머지 mypage 핸들러는 유지한다',
    async () => {
      vi.stubEnv('VITE_ENABLE_MSW', 'hybrid');

      const { allMockHandlers } = await import('./handlers');
      const { mypageHandlers, mypagePasswordChangeHandler } = await import(
        '../features/mypage/api/mypageApi.handlers'
      );

      expect(allMockHandlers).not.toContain(mypagePasswordChangeHandler);
      // 비밀번호 변경 핸들러를 제외한 나머지 mypageHandlers 전부는 여전히 포함돼야 한다.
      const otherMypageHandlers = mypageHandlers.filter(
        (handler) => handler !== mypagePasswordChangeHandler,
      );
      expect(allMockHandlers).toEqual(expect.arrayContaining(otherMypageHandlers));
    },
    15_000,
  );

  it(
    '순수 목 런타임은 비밀번호 변경 핸들러도 전역 handlers에 포함한다',
    async () => {
      vi.stubEnv('VITE_ENABLE_MSW', 'true');

      const { handlers } = await import('./handlers');
      const { mypagePasswordChangeHandler } = await import(
        '../features/mypage/api/mypageApi.handlers'
      );

      expect(handlers).toContain(mypagePasswordChangeHandler);
    },
    15_000,
  );
});
