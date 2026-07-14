// @vitest-environment jsdom
// P1 회귀 방지 테스트 — 401 인터셉터가 /login 경로에서는 재리다이렉트하지 않아야 한다
// (로그인 화면 세션체크·로그인 실패 401이 무한 리로드/에러메시지 미노출로 이어지던 버그)
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { api } from './axios';

const server = setupServer(
  http.get('/api/test-401', () =>
    HttpResponse.json(
      { success: false, data: null, error: { code: 'AUTH_UNAUTHORIZED', message: '인증이 필요합니다.' } },
      { status: 401 },
    ),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

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

describe('axios 401 인터셉터 — /login 재리다이렉트 가드', () => {
  it('/login 경로면 401이어도 window.location.href를 재대입하지 않는다', async () => {
    const { hrefSetter, restore } = mockLocation('/login');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });

  it('/login이 아닌 경로면 401 시 /login으로 리다이렉트한다', async () => {
    const { hrefSetter, restore } = mockLocation('/dashboard');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).toHaveBeenCalledWith('/login');
    } finally {
      restore();
    }
  });

  it('basename 서브패스(/app/login)여도 로그인 경로로 인식해 리다이렉트를 스킵한다', async () => {
    const { hrefSetter, restore } = mockLocation('/app/login');
    try {
      await expect(api.get('/test-401')).rejects.toMatchObject({ code: 'AUTH_UNAUTHORIZED' });
      expect(hrefSetter).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });
});
