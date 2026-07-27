// @vitest-environment jsdom
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
