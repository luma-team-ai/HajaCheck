import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { COMPANY_OPTIONS, DEFAULT_PAGE_SIZE, PLAN_LABEL, ROLE_LABEL, STATUS_LABEL } from '../constants';
import { mockPlatformAdminUsers, mockPlatformAdminUserStats } from '../mocks/platformAdminUsers.mock';
import type {
  AdminUser,
  AdminUserListResponse,
  AdminUserPlan,
  AdminUserRole,
  AdminUserStatus,
} from '../types';

// 플랫폼 관리자 > 사용자 관리(#577) — features/admin/api/adminApi.handlers.ts(#405)를 그대로
// 옮긴 것. 실 백엔드 /api/platform-admin/users가 준비되기 전까지(backend/576) 로컬 개발/테스트는
// 이 MSW 목이 응답한다.

const VALID_ROLES = new Set(Object.keys(ROLE_LABEL));
const VALID_PLANS = new Set(Object.keys(PLAN_LABEL));
const VALID_STATUSES = new Set(Object.keys(STATUS_LABEL));

function parseEnumParam<T extends string>(value: string | null, valid: Set<string>): T | null {
  return value !== null && valid.has(value) ? (value as T) : null;
}

function matchesKeyword(user: AdminUser, keyword: string): boolean {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return (
    user.name.toLowerCase().includes(normalized) ||
    user.email.toLowerCase().includes(normalized) ||
    (user.companyName?.toLowerCase().includes(normalized) ?? false)
  );
}

export const platformAdminUserHandlers = [
  http.get('/api/platform-admin/users', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? DEFAULT_PAGE_SIZE);
    const keyword = url.searchParams.get('keyword') ?? '';
    const role = parseEnumParam<AdminUserRole>(url.searchParams.get('role'), VALID_ROLES);
    const plan = parseEnumParam<AdminUserPlan>(url.searchParams.get('plan'), VALID_PLANS);
    const status = parseEnumParam<AdminUserStatus>(url.searchParams.get('status'), VALID_STATUSES);

    const filtered = mockPlatformAdminUsers.filter(
      (user) =>
        matchesKeyword(user, keyword) &&
        (!role || user.role === role) &&
        (!plan || user.plan === plan) &&
        (!status || user.status === status),
    );

    const start = page * size;
    const body: ApiResponse<AdminUserListResponse> = {
      success: true,
      data: {
        content: filtered.slice(start, start + size),
        page,
        size,
        totalElements: filtered.length,
        stats: mockPlatformAdminUserStats,
      },
    };
    return HttpResponse.json(body);
  }),

  http.post('/api/platform-admin/users', async ({ request }) => {
    const { email, name, role, companyId } = (await request.json()) as {
      email: string;
      name: string;
      role: AdminUserRole;
      companyId: number | null;
    };

    if (mockPlatformAdminUsers.some((candidate) => candidate.email === email)) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: 'AUTH_EMAIL_DUPLICATED', message: '이미 사용 중인 이메일입니다.' },
        },
        { status: 409 },
      );
    }

    const company = companyId !== null ? COMPANY_OPTIONS.find((c) => c.id === companyId) : undefined;
    const created: AdminUser = {
      id: Math.max(0, ...mockPlatformAdminUsers.map((user) => user.id)) + 1,
      name,
      email,
      role,
      plan: null,
      companyId: company?.id ?? null,
      companyName: company?.name ?? null,
      joinedAt: new Date().toISOString(),
      lastAccessAt: null,
      status: 'ACTIVE',
    };
    mockPlatformAdminUsers.unshift(created);

    const body: ApiResponse<AdminUser> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),

  http.patch('/api/platform-admin/users/:id/role', async ({ params, request }) => {
    const id = Number(params.id);
    const user = mockPlatformAdminUsers.find((candidate) => candidate.id === id);
    if (!user) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'USER_NOT_FOUND', message: '사용자를 찾을 수 없습니다.' } },
        { status: 404 },
      );
    }
    const { role } = (await request.json()) as { role: AdminUserRole };
    user.role = role;
    const body: ApiResponse<{ id: number; role: AdminUserRole }> = {
      success: true,
      data: { id, role },
    };
    return HttpResponse.json(body);
  }),

  http.patch('/api/platform-admin/users/:id/status', async ({ params, request }) => {
    const id = Number(params.id);
    const user = mockPlatformAdminUsers.find((candidate) => candidate.id === id);
    if (!user) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'USER_NOT_FOUND', message: '사용자를 찾을 수 없습니다.' } },
        { status: 404 },
      );
    }
    const { status } = (await request.json()) as { status: AdminUserStatus };
    user.status = status;
    const body: ApiResponse<{ id: number; status: AdminUserStatus }> = {
      success: true,
      data: { id, status },
    };
    return HttpResponse.json(body);
  }),
];
