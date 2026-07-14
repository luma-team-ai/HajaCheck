// @vitest-environment jsdom
// axios가 baseURL='/api'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { authApi } from './authApi';
import { authHandlers } from './authApi.handlers';

const server = setupServer(...authHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('authApi.login', () => {
  it('올바른 자격증명이면 UserResponse를 반환한다', async () => {
    const res = await authApi.login({ loginId: 'hajacheck', password: 'password1234' });

    expect(res.data).toMatchObject({
      email: 'hajacheck@example.com',
      role: 'USER',
    });
  });

  it('틀린 자격증명이면 AUTH_INVALID_CREDENTIALS 에러로 reject된다', async () => {
    await expect(
      authApi.login({ loginId: 'hajacheck', password: 'wrong-password' }),
    ).rejects.toMatchObject({
      code: 'AUTH_INVALID_CREDENTIALS',
    });
  });
});
