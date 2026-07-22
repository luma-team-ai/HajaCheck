// @vitest-environment jsdom
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { AdminUserListResponse } from '../types';
import { adminHandlers } from './adminApi.handlers';

const server = setupServer(...adminHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

// #399 — role/plan/status 쿼리 파라미터는 화이트리스트를 통과해야만 필터로 반영된다.
// 유효하지 않은 값이 들어오면 필터를 적용하지 않은 것(=전체 조회)과 같아야 한다(조용히 오조회하지 않음).
describe('GET /api/admin/users 쿼리 파라미터 검증', () => {
  it('유효하지 않은 role 값은 무시하고 전체를 반환한다', async () => {
    const res = await fetch('/api/admin/users?role=NOT_A_ROLE&size=100');
    const body = (await res.json()) as ApiResponse<AdminUserListResponse>;

    expect(body.success).toBe(true);
    expect(body.data?.totalElements).toBe(24);
  });

  it('유효한 role 값은 정상적으로 필터링된다', async () => {
    const res = await fetch('/api/admin/users?role=ADMIN&size=100');
    const body = (await res.json()) as ApiResponse<AdminUserListResponse>;

    expect(body.success).toBe(true);
    expect(body.data?.content.every((user) => user.role === 'ADMIN')).toBe(true);
    expect(body.data?.totalElements).toBeGreaterThan(0);
  });

  it('유효하지 않은 status 값은 무시하고 전체를 반환한다', async () => {
    const res = await fetch('/api/admin/users?status=DELETED&size=100');
    const body = (await res.json()) as ApiResponse<AdminUserListResponse>;

    expect(body.data?.totalElements).toBe(24);
  });

  it('유효하지 않은 plan 값은 무시하고 전체를 반환한다', async () => {
    const res = await fetch('/api/admin/users?plan=PREMIUM&size=100');
    const body = (await res.json()) as ApiResponse<AdminUserListResponse>;

    expect(body.data?.totalElements).toBe(24);
  });
});
