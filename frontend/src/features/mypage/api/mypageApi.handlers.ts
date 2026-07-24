import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import {
  MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
  mockMyInspectionRows,
  mockMyInspectionsSummary,
  mockMyReports,
} from '../mocks/myInspections.mock';
import { mockMyPlan, mockSeats } from '../mocks/mypage.mock';
import type {
  InspectionHistoryRow,
  MyInspectionsSummary,
  MyPlan,
  MyReportCard,
  PlanName,
  SeatsInfo,
} from '../types';

// STANDARD/ENTERPRISE 월 구독가 — platform-admin 시드값(planPolicyApi.handlers.ts)과 동일 기준.
// 모의 결제 응답 전용(실 결제 금액은 BE plan.priceMonthly가 source of truth).
const CHECKOUT_PLAN_PRICE: Partial<Record<PlanName, number>> = {
  STANDARD: 29000,
  ENTERPRISE: 59000,
};

function addOneMonthIso(date: Date): string {
  const next = new Date(date);
  next.setMonth(next.getMonth() + 1);
  return next.toISOString().slice(0, 10);
}

// 체크아웃 성공 시 이후 GET /api/me/plan 재조회(useCheckout invalidate)에도 갱신된 값이 보이도록
// 모듈 스코프 상태로 들고 있는다(#624 platform-admin planPolicyApi.handlers.ts와 동일 패턴 — 실 DB
// 아닌 MSW 인메모리 한계, 새로고침하면 mockMyPlan 초기값으로 돌아간다).
let mockMyPlanState: MyPlan = mockMyPlan;

export const mypageHandlers = [
  http.get('/api/me/plan', () => {
    const body: ApiResponse<MyPlan> = { success: true, data: mockMyPlanState };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/seats', () => {
    const body: ApiResponse<SeatsInfo> = { success: true, data: mockSeats };
    return HttpResponse.json(body);
  }),

  // 모의 결제(PG 미연동, #712) — planName 반영해 갱신된 MyPlan을 반환한다. FREE·미지정 플랜은
  // 실 BE와 동일하게 400(INVALID_INPUT)으로 거부한다(대상은 STANDARD/ENTERPRISE만).
  http.post('/api/me/plan/checkout', async ({ request }) => {
    const { planName } = (await request.json()) as { planName?: PlanName };
    const price = planName ? CHECKOUT_PLAN_PRICE[planName] : undefined;

    if (!planName || price === undefined) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INVALID_INPUT', message: '업그레이드할 수 없는 플랜입니다.' },
      };
      return HttpResponse.json(body, { status: 400 });
    }

    mockMyPlanState = {
      ...mockMyPlanState,
      plan: {
        ...mockMyPlanState.plan,
        name: planName,
        priceMonthly: price,
        status: 'ACTIVE',
        nextBillingDate: addOneMonthIso(new Date()),
      },
    };

    const body: ApiResponse<MyPlan> = { success: true, data: mockMyPlanState };
    return HttpResponse.json(body);
  }),

  // 내 점검 이력 / 보고서 (HAJA-366, #668) — BE 미구현이라 page/size 쿼리 파라미터는 실제로
  // 반영하지 않고 항상 같은 8건 + totalElements=18(mock 표시용)을 반환한다.
  http.get('/api/me/inspections/summary', () => {
    const body: ApiResponse<MyInspectionsSummary> = { success: true, data: mockMyInspectionsSummary };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/inspections', () => {
    const page: PageResponse<InspectionHistoryRow> = {
      content: mockMyInspectionRows,
      page: 0,
      totalElements: MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
    };
    const body: ApiResponse<PageResponse<InspectionHistoryRow>> = { success: true, data: page };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/reports', () => {
    const body: ApiResponse<MyReportCard[]> = { success: true, data: mockMyReports };
    return HttpResponse.json(body);
  }),
];
