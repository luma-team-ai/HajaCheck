import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import {
  MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
  mockMyInspectionRows,
  mockMyInspectionsSummary,
  mockMyReports,
} from '../mocks/myInspections.mock';
import { mockMyPlan, mockPayments, mockSeats } from '../mocks/mypage.mock';
import type {
  InspectionHistoryRow,
  MyInspectionsSummary,
  MyPlan,
  MyReportCard,
  PaymentHistoryItem,
  PlanName,
  PlanOrder,
  SeatsInfo,
} from '../types';

// STANDARD/ENTERPRISE 월 구독가 — platform-admin 시드값(planPolicyApi.handlers.ts)과 동일 기준.
// 주문 생성(POST /me/plan/orders) 응답 전용(실 결제 금액은 BE plan.priceMonthly가 source of truth,
// 클라이언트는 이 값을 UI에 미리 노출하지 않는다 — handoff §2 UPGRADE_PLAN_PRICE 하드코딩 삭제 참고).
const CHECKOUT_PLAN_PRICE: Partial<Record<PlanName, number>> = {
  STANDARD: 29000,
  ENTERPRISE: 59000,
};

function addOneMonthIso(date: Date): string {
  const next = new Date(date);
  next.setMonth(next.getMonth() + 1);
  return next.toISOString().slice(0, 10);
}

// 결제 승인 성공 시 이후 GET /api/me/plan 재조회(useConfirmPayment invalidate)에도 갱신된 값이 보이도록
// 모듈 스코프 상태로 들고 있는다(#624 platform-admin planPolicyApi.handlers.ts와 동일 패턴 — 실 DB
// 아닌 MSW 인메모리 한계, 새로고침하면 mockMyPlan 초기값으로 돌아간다).
let mockMyPlanState: MyPlan = mockMyPlan;

// 토스페이먼츠 결제창 연동(#989, HAJA-490) — 주문 생성(orders) → 결제 승인(payments/confirm) 2단계를
// MSW로 흉내낸다. 실 백엔드처럼 orderId로 금액을 검증(PAYMENT_AMOUNT_MISMATCH)하고, 존재하지 않는
// orderId는 PAYMENT_ORDER_NOT_FOUND(404)로 거부한다. confirmedOrderIds는 새로고침·중복 진입 시
// 동일 orderId로 다시 confirm이 들어와도(멱등) 결제 내역이 중복 적재되지 않게 막는다.
let mockOrderSeq = 1;
let mockPaymentIdSeq = mockPayments.length + 1;
const mockOrders = new Map<string, { planName: PlanName; amount: number; orderName: string }>();
const confirmedOrderIds = new Set<string>();
const mockPaymentsState: PaymentHistoryItem[] = [...mockPayments];

export const mypageHandlers = [
  http.get('/api/me/plan', () => {
    const body: ApiResponse<MyPlan> = { success: true, data: mockMyPlanState };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/seats', () => {
    const body: ApiResponse<SeatsInfo> = { success: true, data: mockSeats };
    return HttpResponse.json(body);
  }),

  // 토스페이먼츠 결제창 연동 1단계 — 주문 생성(#989, HAJA-490). FREE·미지정 플랜은 실 BE와
  // 동일하게 400(INVALID_INPUT)으로 거부한다(대상은 STANDARD/ENTERPRISE만). 응답 amount가
  // requestPayment()에 그대로 전달돼 결제창에 표시되는 금액이 된다.
  http.post('/api/me/plan/orders', async ({ request }) => {
    const { planName } = (await request.json()) as { planName?: PlanName };
    const amount = planName ? CHECKOUT_PLAN_PRICE[planName] : undefined;

    if (!planName || amount === undefined) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INVALID_INPUT', message: '업그레이드할 수 없는 플랜입니다.' },
      };
      return HttpResponse.json(body, { status: 400 });
    }

    const orderId = `order_mock_${mockOrderSeq++}`;
    const orderName = `hajaCheck ${planName} 플랜 구독`;
    mockOrders.set(orderId, { planName, amount, orderName });

    const body: ApiResponse<PlanOrder> = {
      success: true,
      data: { orderId, planName, amount, orderName },
    };
    return HttpResponse.json(body);
  }),

  // 토스페이먼츠 결제창 연동 2단계 — 결제 승인(#989, HAJA-490). 존재하지 않는 orderId는
  // PAYMENT_ORDER_NOT_FOUND(404), 요청 amount가 주문 생성 시 금액과 다르면 PAYMENT_AMOUNT_MISMATCH
  // (400)로 거부한다. 동일 orderId로 재요청(새로고침·중복 진입)해도 결제 내역이 중복 적재되지
  // 않고 동일한 MyPlan을 그대로 반환한다(handoff §3 멱등 요구사항).
  http.post('/api/me/payments/confirm', async ({ request }) => {
    const { paymentKey, orderId, amount } = (await request.json()) as {
      paymentKey?: string;
      orderId?: string;
      amount?: number;
    };

    const order = orderId ? mockOrders.get(orderId) : undefined;
    if (!order) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'PAYMENT_ORDER_NOT_FOUND', message: '결제 주문 정보를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(body, { status: 404 });
    }

    if (amount !== order.amount) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'PAYMENT_AMOUNT_MISMATCH', message: '결제 금액이 일치하지 않습니다.' },
      };
      return HttpResponse.json(body, { status: 400 });
    }

    if (orderId && !confirmedOrderIds.has(orderId)) {
      confirmedOrderIds.add(orderId);
      mockPaymentsState.unshift({
        id: mockPaymentIdSeq++,
        orderId,
        planName: order.planName,
        amount: order.amount,
        status: 'PAID',
        method: '카드',
        approvedAt: new Date().toISOString(),
        receiptUrl: `https://mock.tosspayments.com/receipt/${paymentKey}`,
      });

      mockMyPlanState = {
        ...mockMyPlanState,
        plan: {
          ...mockMyPlanState.plan,
          name: order.planName,
          priceMonthly: order.amount,
          status: 'ACTIVE',
          nextBillingDate: addOneMonthIso(new Date()),
        },
      };
    }

    const body: ApiResponse<MyPlan> = { success: true, data: mockMyPlanState };
    return HttpResponse.json(body);
  }),

  // 결제 내역 실연동(#864) — 최신순(mockPaymentsState.unshift로 항상 최신이 앞에 온다).
  http.get('/api/me/payments', () => {
    const body: ApiResponse<{ payments: PaymentHistoryItem[] }> = {
      success: true,
      data: { payments: mockPaymentsState },
    };
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
