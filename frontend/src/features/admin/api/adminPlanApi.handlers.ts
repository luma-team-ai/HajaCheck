import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type { AdminCurrentPlanResponse, AdminPlanCatalogResponse } from '../planQuota.types';

// GET /api/admin/plans MSW 목 — docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql의
// 실제 시드값과 동일하게 맞춘다(PRD_hajaCheck.md §2.4 요금제 표 기준, HAJA-308). FREE는 max_seats=1
// (계정 소유자 본인 1석, "추가 초대 좌석 없음"을 의미), ENTERPRISE는 max_seats=null(무제한)이다.
export const mockAdminPlanCatalog: AdminPlanCatalogResponse = {
  plans: [
    {
      id: 1,
      name: 'FREE',
      maxFacilities: 1,
      maxMonthlyAnalyses: 50,
      maxSeats: 1,
      hasPdfWatermark: true,
      hasCounselorAccess: false,
      hasAiAddon: false,
      priceMonthly: 0,
    },
    {
      id: 2,
      name: 'STANDARD',
      maxFacilities: 10,
      maxMonthlyAnalyses: 1000,
      maxSeats: 3,
      hasPdfWatermark: false,
      hasCounselorAccess: true,
      hasAiAddon: true,
      priceMonthly: 29000,
    },
    {
      id: 3,
      name: 'ENTERPRISE',
      maxFacilities: null,
      maxMonthlyAnalyses: null,
      maxSeats: null,
      hasPdfWatermark: false,
      hasCounselorAccess: true,
      hasAiAddon: true,
      priceMonthly: 59000,
    },
  ],
};

// GET /api/admin/plan MSW 목 — 좌석 여유가 있는 STANDARD 기본값(1/3석)으로 둔다. 좌석이 가득 찬
// 케이스를 확인하는 테스트는 server.use()로 이 핸들러를 개별 덮어써 재현한다(#872 후속).
export const mockAdminCurrentPlan: AdminCurrentPlanResponse = {
  subscriptionId: 1,
  plan: mockAdminPlanCatalog.plans[1],
  status: 'ACTIVE',
  startedAt: '2026-01-01T00:00:00Z',
  usage: {
    analyzedImageCount: 0,
    analysisRequestCount: 0,
    facilityCount: 0,
    seatCount: 1,
    period: '2026-07-01',
  },
};

export const adminPlanHandlers = [
  http.get('/api/admin/plans', () => {
    const body: ApiResponse<AdminPlanCatalogResponse> = { success: true, data: mockAdminPlanCatalog };
    return HttpResponse.json(body);
  }),
  http.get('/api/admin/plan', () => {
    const body: ApiResponse<AdminCurrentPlanResponse> = { success: true, data: mockAdminCurrentPlan };
    return HttpResponse.json(body);
  }),
];
