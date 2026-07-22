import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { DEFAULT_PAGE_SIZE } from '../constants';
import { mockAdminUserStats, mockAdminUsers } from '../mocks/adminUsers.mock';
import type {
  AdminUser,
  AdminUserListResponse,
  AdminUserPlan,
  AdminUserRole,
  AdminUserStatus,
} from '../types';

// 백엔드 GET /api/admin/users(#405) 구현 완료 — 이 핸들러는 VITE_ENABLE_MSW=false로 끄지 않은
// 로컬 개발/테스트에서만 쓰이는 목 폴백이다. 검색·필터·페이징을 서버와 동일한 위치(서버 측)에서
// 처리해 화면 코드가 실 API와 그대로 맞물리게 한다(프론트 전량 로드 후 클라이언트 필터링 구조였다면
// 나중에 서버 페이징으로 바꿀 때 화면을 다시 써야 했을 것).
// page 파라미터는 실 백엔드와 동일하게 0-base(adminApi.ts가 UI의 1-base 상태를 여기서 변환해 보낸다).

function matchesKeyword(user: AdminUser, keyword: string): boolean {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return (
    user.name.toLowerCase().includes(normalized) ||
    user.email.toLowerCase().includes(normalized)
  );
}

export const adminHandlers = [
  http.get('/api/admin/users', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? DEFAULT_PAGE_SIZE);
    const keyword = url.searchParams.get('keyword') ?? '';
    const role = url.searchParams.get('role') as AdminUserRole | null;
    const plan = url.searchParams.get('plan') as AdminUserPlan | null;
    const status = url.searchParams.get('status') as AdminUserStatus | null;

    const filtered = mockAdminUsers.filter(
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
        // 통계 카드는 필터와 무관한 전체 기준 집계 — 필터링된 목록으로 다시 계산하지 않는다
        stats: mockAdminUserStats,
      },
    };
    return HttpResponse.json(body);
  }),

  // POST /api/admin/users(#405 사용자 등록) — mockAdminUsers에 새 행을 추가해, 저장 후 목록을
  // 다시 불러오면(react-query invalidate) 새 사용자가 실제로 반영되도록 한다.
  http.post('/api/admin/users', async ({ request }) => {
    const { email, name, role } = (await request.json()) as {
      email: string;
      name: string;
      role: AdminUserRole;
    };

    if (mockAdminUsers.some((candidate) => candidate.email === email)) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: 'AUTH_EMAIL_DUPLICATED', message: '이미 사용 중인 이메일입니다.' },
        },
        { status: 409 },
      );
    }

    const created: AdminUser = {
      id: Math.max(0, ...mockAdminUsers.map((user) => user.id)) + 1,
      name,
      email,
      role,
      plan: null,
      joinedAt: new Date().toISOString(),
      lastAccessAt: null,
      status: 'ACTIVE',
    };
    mockAdminUsers.unshift(created);

    const body: ApiResponse<AdminUser> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),

  // PATCH /api/admin/users/:id/role, /:id/status(#405 역할·상태 변경) — mockAdminUsers를 직접
  // 갱신해, 저장 후 목록을 다시 불러오면(react-query invalidate) 변경 결과가 실제로 반영되도록 한다.
  http.patch('/api/admin/users/:id/role', async ({ params, request }) => {
    const id = Number(params.id);
    const user = mockAdminUsers.find((candidate) => candidate.id === id);
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

  http.patch('/api/admin/users/:id/status', async ({ params, request }) => {
    const id = Number(params.id);
    const user = mockAdminUsers.find((candidate) => candidate.id === id);
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
