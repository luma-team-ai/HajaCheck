// @vitest-environment jsdom
// P1 회귀 방지 테스트 — 401 인터셉터가 로그인 경로에서는 재리다이렉트하지 않아야 한다
// (로그인 화면 세션체크·로그인 실패 401이 무한 리로드/에러메시지 미노출로 이어지던 버그)
// LOGIN_PATH는 import.meta.env.BASE_URL(vite base)을 반영해 계산되므로,
// basename 배포 시나리오를 검증하려면 vi.stubEnv + vi.resetModules로 모듈을 다시 임포트해야 한다.
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const server = setupServer(
  http.get('/api/test-401', () =>
    HttpResponse.json(
      { success: false, data: null, error: { code: 'AUTH_UNAUTHORIZED', message: '인증이 필요합니다.' } },
      { status: 401 },
    ),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  vi.unstubAllEnvs();
});
afterAll(() => server.close());

async function importFreshApi() {
  vi.resetModules();
  const mod = await import('./axios');
  return mod.api;
}

// jsdom의 Location 인스턴스는 href/pathname 접근자가 non-configurable이라 직접 재정의 불가.
// 대신 원본 Location을 Proxy로 감싸 pathname 읽기·href 쓰기만 가로채고,
// origin 등 나머지 속성·메서드는 Reflect로 원본에 위임 — axios/MSW의 상대경로 URL 해석은 그대로 유지
function mockLocation(pathname: string) {
  const hrefSetter = vi.fn();
  const original = window.location;

  const proxyLocation = new Proxy(original, {
    get(target, prop, receiver) {
      if (prop === 'pathname') return pathname;
      return Reflect.get(target, prop, receiver);
    },
    set(target, prop, value, receiver) {
      if (prop === 'href') {
        hrefSetter(value);
        return true;
      }
      return Reflect.set(target, prop, value, receiver);
    },
  });

  Object.defineProperty(window, 'location', { configurable: true, value: proxyLocation });

  return {
    hrefSetter,
    restore: () => {
      Object.defineProperty(window, 'location', { configurable: true, value: original });
    },
  };
}

describe('axios 401 인터셉터 — 로그인 경로 가드 (기본 base=/)', () => {
  it('/login 경로면 401이어도 window.location.href를 재대입하지 않는다', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/login');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });

  it('/login이 아닌 경로면 401 시 /login으로 리다이렉트한다', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/dashboard');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/login');
    } finally {
      restore();
    }
  });

  it('skipAuthRedirect면 /login이 아닌 공개 경로(랜딩 "/")에서 401이어도 리다이렉트하지 않는다 (#276)', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/');
    try {
      await expect(api.get('/test-401', { skipAuthRedirect: true })).rejects.toMatchObject({
        code: 'AUTH_UNAUTHORIZED',
      });
      expect(hrefSetter).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });

  it('skipAuthRedirect가 없으면 기존대로 /login이 아닌 경로에서 401 시 리다이렉트한다 (회귀 대비)', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/login');
    } finally {
      restore();
    }
  });

  it('과매칭 방지 — /company/login처럼 "login"으로 끝나지만 다른 경로여도 정확 비교로 리다이렉트한다', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/company/login');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/login');
    } finally {
      restore();
    }
  });

  it('ApiError에 HTTP status(401)를 포함한다', async () => {
    const api = await importFreshApi();
    const { restore } = mockLocation('/login');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ status: 401 });
    } finally {
      restore();
    }
  });
});

describe('axios 401 인터셉터 — basename 배포(base=/app/)', () => {
  it('/app/login 경로면 401이어도 리다이렉트하지 않는다', async () => {
    vi.stubEnv('BASE_URL', '/app/');
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/app/login');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });

  it('/app/dashboard면 리다이렉트 대상도 /app/login(basename 반영)이 된다', async () => {
    vi.stubEnv('BASE_URL', '/app/');
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/app/dashboard');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/app/login');
    } finally {
      restore();
    }
  });

  // PR머신 리뷰 P2(#558) — 플랫폼 관리자 경로 판별이 raw '/platform-admin'만 비교하면 basename
  // 배포에서 항상 false가 되어 기업회원 /app/login으로 잘못 리다이렉트되던 회귀를 고정한다.
  it('/app/platform-admin/users면 /app/platform-admin/login으로 리다이렉트한다(basename 반영)', async () => {
    vi.stubEnv('BASE_URL', '/app/');
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/app/platform-admin/users');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/app/platform-admin/login');
    } finally {
      restore();
    }
  });
});

describe('axios 401 인터셉터 — 플랫폼 관리자 콘솔(#535, base=/)', () => {
  it('/platform-admin 하위 경로에서 401이면 /platform-admin/login으로 리다이렉트한다(일반 /login이 아님)', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/platform-admin/users');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/platform-admin/login');
    } finally {
      restore();
    }
  });

  it('/platform-admin/login 경로면 401이어도 재대입하지 않는다(무한 리로드 방지)', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/platform-admin/login');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });

  it('일반 경로(/dashboard)는 여전히 기업회원 /login으로 리다이렉트한다(회귀 방지)', async () => {
    const api = await importFreshApi();
    const { hrefSetter, restore } = mockLocation('/dashboard');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/login');
    } finally {
      restore();
    }
  });
});
