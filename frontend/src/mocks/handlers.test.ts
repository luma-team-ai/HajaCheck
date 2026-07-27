import { afterEach, describe, expect, it, vi } from 'vitest';

describe('MSW handler exports', () => {
  afterEach(() => {
    vi.resetModules();
    vi.unstubAllEnvs();
  });

  it('hybrid 런타임은 브라우저용 handlers만 비우고 테스트용 전체 집합은 유지한다', async () => {
    vi.stubEnv('VITE_ENABLE_MSW', 'hybrid');

    const { allMockHandlers, handlers } = await import('./handlers');

    expect(handlers).toEqual([]);
    expect(allMockHandlers.length).toBeGreaterThan(0);
  });
});
