import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { PLAN_QUOTA_DEFAULT_PAGE_SIZE } from '../planQuota.constants';
import { mockPlanQuotaStats, mockPlanQuotaUsers } from '../mocks/planQuotaUsers.mock';
import type { PlanQuotaListResponse, PlanQuotaUser } from '../planQuota.types';

// 백엔드 /api/platform-admin/plans-quota 미구현 구간의 MSW 핸들러 — 검색·페이징을 서버와 동일한
// 위치(서버 측)에서 처리해, 실제 API로 교체할 때 화면 코드가 그대로 남도록 한다.

function matchesKeyword(user: PlanQuotaUser, keyword: string): boolean {
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

export const planQuotaHandlers = [
  http.get('/api/platform-admin/plans-quota', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 1);
    const size = Number(url.searchParams.get('size') ?? PLAN_QUOTA_DEFAULT_PAGE_SIZE);
    const keyword = url.searchParams.get('keyword') ?? '';
    const plan = url.searchParams.get('plan');

    const filtered = mockPlanQuotaUsers
      .filter((user) => matchesKeyword(user, keyword))
      .filter((user) => !plan || user.plan === plan);

    const start = (page - 1) * size;
    const body: ApiResponse<PlanQuotaListResponse> = {
      success: true,
      data: {
        content: filtered.slice(start, start + size),
        page,
        size,
        totalElements: filtered.length,
        // KPI 카드는 검색어와 무관한 전체 기준 집계 — 필터링된 목록으로 다시 계산하지 않는다
        stats: mockPlanQuotaStats,
      },
    };
    return HttpResponse.json(body);
  }),
];
