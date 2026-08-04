// @vitest-environment jsdom
// axios가 baseURL='/api'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { authApi } from './authApi';
import { authHandlers } from './authApi.handlers';

const server = setupServer(...authHandlers);

// 실제로 어떤 경로로 나갔는지 기록한다 — 이 PR의 핵심 계약이 "화면별 엔드포인트 분리"(#1513)라,
// 메서드가 조용히 다른 경로로 POST 하면 목/실서버 어느 쪽에서도 role 게이트가 무력화된다.
const requestedPaths: string[] = [];
server.events.on('request:start', ({ request }) => {
  requestedPaths.push(new URL(request.url).pathname);
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  requestedPaths.length = 0;
  window.sessionStorage.clear();
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('authApi.login (기업회원 포털)', () => {
  it('올바른 자격증명이면 UserResponse를 반환한다', async () => {
    const res = await authApi.login({ loginId: 'hajacheck', password: 'password1234' });

    expect(res.data).toMatchObject({
      email: 'hajacheck@example.com',
      role: 'USER',
    });
  });

  it('POST /api/auth/login으로 보낸다(경로 회귀 방지)', async () => {
    await authApi.login({ loginId: 'hajacheck', password: 'password1234' });

    expect(requestedPaths).toEqual(['/api/auth/login']);
  });

  it('틀린 자격증명이면 AUTH_INVALID_CREDENTIALS 에러로 reject된다', async () => {
    await expect(
      authApi.login({ loginId: 'hajacheck', password: 'wrong-password' }),
    ).rejects.toMatchObject({
      code: 'AUTH_INVALID_CREDENTIALS',
    });
  });

  // #1513 — 서버가 포털별 허용 role을 강제한다. 자격증명이 맞아도 허용 role이 아니면 403이며
  // 세션은 발급되지 않는다(프론트가 되돌릴 세션이 없다는 것이 이 설계의 전제).
  it('허용 role이 아니면 403 AUTH_ROLE_NOT_ALLOWED로 reject된다', async () => {
    await expect(
      authApi.login({ loginId: 'platform-admin', password: 'password1234' }),
    ).rejects.toMatchObject({
      code: 'AUTH_ROLE_NOT_ALLOWED',
      status: 403,
    });
  });

  // 목이 계정부를 plain object로 인덱싱하면 'toString'·'__proto__' 같은 loginId에 프로토타입
  // 체인 값이 잡혀, 401이어야 할 응답이 403(계정은 있으나 role 불가)으로 나간다 — 목이 실서버와
  // 다른 계약을 흉내 내면 그 위에서 검증한 화면 동작을 믿을 수 없다.
  it.each(['toString', 'constructor', '__proto__', 'hasOwnProperty'])(
    '프로토타입 체인 키(%s)를 아이디로 보내도 계정 없음(401)으로 처리한다',
    async (loginId) => {
      await expect(authApi.login({ loginId, password: 'password1234' })).rejects.toMatchObject({
        code: 'AUTH_INVALID_CREDENTIALS',
        status: 401,
      });
    },
  );
});

describe('authApi.platformAdminLogin (플랫폼 관리자 포털)', () => {
  it('POST /api/auth/platform-admin/login으로 보내고 PLATFORM_ADMIN을 반환한다', async () => {
    const res = await authApi.platformAdminLogin({
      loginId: 'platform-admin',
      password: 'password1234',
    });

    expect(requestedPaths).toEqual(['/api/auth/platform-admin/login']);
    expect(res.data).toMatchObject({ role: 'PLATFORM_ADMIN' });
  });

  it('PLATFORM_ADMIN이 아니면 403 AUTH_ROLE_NOT_ALLOWED로 reject된다', async () => {
    await expect(
      authApi.platformAdminLogin({ loginId: 'hajacheck', password: 'password1234' }),
    ).rejects.toMatchObject({
      code: 'AUTH_ROLE_NOT_ALLOWED',
      status: 403,
    });
  });
});

describe('authApi.counselorLogin (상담원 포털)', () => {
  it('POST /api/auth/counselor/login으로 보내고 COUNSELOR를 반환한다', async () => {
    const res = await authApi.counselorLogin({ loginId: 'counselor', password: 'password1234' });

    expect(requestedPaths).toEqual(['/api/auth/counselor/login']);
    expect(res.data).toMatchObject({ role: 'COUNSELOR' });
  });

  it('COUNSELOR가 아니면 403 AUTH_ROLE_NOT_ALLOWED로 reject된다', async () => {
    await expect(
      authApi.counselorLogin({ loginId: 'hajacheck', password: 'password1234' }),
    ).rejects.toMatchObject({
      code: 'AUTH_ROLE_NOT_ALLOWED',
      status: 403,
    });
  });
});
