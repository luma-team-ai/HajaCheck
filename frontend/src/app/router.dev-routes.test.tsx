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

describe('개발 전용 라우트', () => {
  it('개발 환경에는 차트 쇼케이스를 등록한다', async () => {
    const router = await loadRouter(true);

    expect(router.routes.some((route) => route.path === '/dev/charts')).toBe(true);
    router.dispose();
  });

  it('운영 환경에는 차트 쇼케이스를 등록하지 않는다', async () => {
    const router = await loadRouter(false);

    expect(router.routes.some((route) => route.path === '/dev/charts')).toBe(false);
    router.dispose();
  });
});
