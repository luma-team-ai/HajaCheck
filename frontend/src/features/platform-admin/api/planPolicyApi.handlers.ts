import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type { PlanPolicyApiItem } from '../planPolicy.mapper';

// GET/PUT /api/platform-admin/plans MSW 목 — 백엔드 /api/admin/plans(#507) 시드값과 동일 기준
// (docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql). 모듈 스코프 변수로 PUT 결과를
// 들고 있어(#624 후속, 사용자 지시) 저장 후 재조회하면 바뀐 값이 그대로 보인다 — 새로고침하면
// 초기값으로 돌아간다(실 DB 아닌 MSW 인메모리 한계, 실 백엔드 확인 전까지의 개발 편의용).
let mockPlans: PlanPolicyApiItem[] = [
  {
    name: 'FREE',
    priceMonthly: 0,
    maxFacilities: 1,
    maxMonthlyAnalyses: 50,
    maxSeats: 1,
    hasPdfWatermark: true,
    hasCounselorAccess: false,
  },
  {
    name: 'STANDARD',
    priceMonthly: 29000,
    maxFacilities: 10,
    maxMonthlyAnalyses: 1000,
    maxSeats: 3,
    hasPdfWatermark: false,
    hasCounselorAccess: true,
  },
  {
    name: 'ENTERPRISE',
    priceMonthly: 59000,
    maxFacilities: null,
    maxMonthlyAnalyses: null,
    maxSeats: null,
    hasPdfWatermark: false,
    hasCounselorAccess: true,
  },
];

export const planPolicyHandlers = [
  http.get('/api/platform-admin/plans', () => {
    const body: ApiResponse<{ plans: PlanPolicyApiItem[] }> = {
      success: true,
      data: { plans: mockPlans },
    };
    return HttpResponse.json(body);
  }),
  http.put('/api/platform-admin/plans', async ({ request }) => {
    const payload = (await request.json()) as { plans: PlanPolicyApiItem[] };
    mockPlans = payload.plans;
    const body: ApiResponse<{ plans: PlanPolicyApiItem[] }> = {
      success: true,
      data: { plans: mockPlans },
    };
    return HttpResponse.json(body);
  }),
];
