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

// 백엔드 /admin/users 미구현 구간의 MSW 핸들러 — 검색·필터·페이징을 서버와 동일한 위치(서버 측)에서
// 처리해, 실제 API로 교체할 때 화면 코드가 그대로 남도록 한다(프론트에서 전량 로드 후 클라이언트
// 필터링하는 구조를 쓰면 나중에 서버 페이징으로 바꿀 때 화면을 다시 써야 한다).

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
    const page = Number(url.searchParams.get('page') ?? 1);
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

    const start = (page - 1) * size;
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
];
