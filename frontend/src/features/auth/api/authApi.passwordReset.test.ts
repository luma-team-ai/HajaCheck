// @vitest-environment jsdom
// axios가 baseURL='/api'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import {
  MOCK_RATE_LIMITED_EMAIL,
  MOCK_VALID_RESET_TOKEN,
  passwordResetHandlers,
} from '../mocks/passwordReset.mock';
import { authApi } from './authApi';

const server = setupServer(...passwordResetHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('authApi.requestPasswordReset', () => {
  it('임의의 이메일이면 항상 { requested: true }를 반환한다(계정 존재 여부 무관 — 계약 §1단계)', async () => {
    const known = await authApi.requestPasswordReset({ email: 'known@check.com' });
    const unknown = await authApi.requestPasswordReset({ email: 'nobody-here@check.com' });

    expect(known.data).toEqual({ requested: true });
    expect(unknown.data).toEqual({ requested: true });
  });

  it('rate-limit 초과 시 429 AUTH_TOO_MANY_REQUESTS로 reject된다', async () => {
    await expect(
      authApi.requestPasswordReset({ email: MOCK_RATE_LIMITED_EMAIL }),
    ).rejects.toMatchObject({
      code: 'AUTH_TOO_MANY_REQUESTS',
      status: 429,
    });
  });
});

describe('authApi.resetPassword', () => {
  it('유효한 토큰이면 { reset: true }를 반환한다', async () => {
    const res = await authApi.resetPassword({
      token: MOCK_VALID_RESET_TOKEN,
      newPassword: 'newpass1234',
    });

    expect(res.data).toEqual({ reset: true });
  });

  it('무효한 토큰이면 400 AUTH_RESET_TOKEN_INVALID로 reject된다', async () => {
    await expect(
      authApi.resetPassword({ token: 'expired-or-used-token', newPassword: 'newpass1234' }),
    ).rejects.toMatchObject({
      code: 'AUTH_RESET_TOKEN_INVALID',
      status: 400,
    });
  });
});
