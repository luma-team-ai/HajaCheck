import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import {
  MOCK_MY_INSPECTIONS_TOTAL_ELEMENTS,
  mockMyInspectionRows,
  mockMyInspectionsSummary,
  mockMyReports,
} from '../mocks/myInspections.mock';
import { mockMyPlan, mockPayments, mockSeats } from '../mocks/mypage.mock';
import { PASSWORD_CHANGE_ERROR_CODE } from '../types';
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
// orderId는 PAYMENT_ORDER_NOT_FOUND(404)로 거부한다(#988 리뷰 픽스 — 비소유자 접근도 403이 아니라
// 이 코드로 온다). confirmedOrderIds는 새로고침·중복 진입 시 동일 orderId로 다시 confirm이
// 들어와도(멱등) 결제 내역이 중복 적재되지 않게 막는다.
let mockOrderSeq = 1;
let mockPaymentIdSeq = mockPayments.length + 1;
// listPrice/credit(#1146, HAJA-550 잔여 기간 일할 크레딧)은 MSW 목에서는 재현하지 않는다 — 계산이
// current_period_start/end(KST 일할)에 의존해 BE 전용이고, FE 목은 계약 형태(listPrice - credit ===
// amount)만 지키면 된다. listPrice === amount(credit 0)로 고정 — 기존 FE 테스트의 amount 기대값을
// 그대로 유지한다.
const mockOrders = new Map<string, { planName: PlanName; amount: number; orderName: string }>();
const confirmedOrderIds = new Set<string>();
// 결제 성공+플랜 반영 실패(PAYMENT_PLAN_APPLY_PENDING) 시나리오로 처리된 주문 — 이후 동일 orderId로
// 재확인해도 같은 결과(같은 코드)를 반환한다(성공으로 뒤바뀌지 않음, 백엔드가 실제로 반영할 때까지).
const pendingApplyOrderIds = new Set<string>();
const mockPaymentsState: PaymentHistoryItem[] = [...mockPayments];

// 결제(승인) 성공 + 플랜 반영만 실패하는 케이스(#988 백엔드 리뷰 픽스, 2026-07-27)는 소속 변경 등
// 백엔드 내부 조건이라 목 데이터로 자연 발생시킬 수 없다 — supportApi.handlers.ts의 "전용 트리거"
// 패턴을 그대로 따라 paymentKey 접두사로 재현한다(테스트에서만 사용).
export const MYPAGE_PAYMENT_DEV_TRIGGER = {
  applyPending: '__apply_pending__',
} as const;

// 테스트에서 setupServer(...mypageHandlers) + afterEach(() => server.resetHandlers())로 격리해도
// 이 모듈 스코프 상태(mockMyPlanState/mockOrders/confirmedOrderIds/pendingApplyOrderIds/
// mockPaymentsState)는 resetHandlers()로 초기화되지 않는다(facilityApi.handlers.ts의
// resetFacilityMockStore와 동일한 한계). 특히 "이미 그 플랜이면 PLAN_ACTIVE_SUBSCRIPTION_CONFLICT"
// 검사가 현재 mockMyPlanState.plan.name에 의존하므로, 한 테스트가 플랜을 바꾸면 이후 다른
// it() 블록의 시나리오(예: 같은 플랜명으로 "정상 승인" 기대)가 실행 순서에 따라 충돌로 잘못
// 거절될 수 있다 — mypageApi.payments.handlers.test.ts/PaymentSuccessPage.test.tsx의
// afterEach(() => { server.resetHandlers(); resetMypagePaymentMockStore(); })에서 사용한다.
export function resetMypagePaymentMockStore(): void {
  mockMyPlanState = mockMyPlan;
  mockOrderSeq = 1;
  mockPaymentIdSeq = mockPayments.length + 1;
  mockOrders.clear();
  confirmedOrderIds.clear();
  pendingApplyOrderIds.clear();
  mockPaymentsState.length = 0;
  mockPaymentsState.push(...mockPayments);
}

// 비밀번호 변경 MSW 전용 트리거 — mockPayments의 MYPAGE_PAYMENT_DEV_TRIGGER와 동일 패턴. 실 백엔드
// 시나리오(계정 상태·rate limit·세션 만료)를 목 데이터로 자연 발생시킬 수 없어 currentPassword 값으로
// 분기한다.
export const MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER = {
  wrongCurrentPassword: '__wrong_current_password__',
  sessionExpired: '__session_expired__',
  unknownAuthError: '__unknown_auth_error__',
  passwordNotSet: '__password_not_set__',
  rateLimited: '__rate_limited__',
  serverError: '__server_error__',
} as const;

// 비밀번호 변경(#1316, HAJA-602) — BE #1315 병렬 구현이라 실 API 대신 handoff 계약대로 흉내낸다.
// 에러 코드는 types.ts의 PASSWORD_CHANGE_ERROR_CODE(실 백엔드 ErrorCode(#1315) 확인 완료 —
// RestAuthenticationEntryPoint→UNAUTHORIZED, PasswordChangeService:90→AUTH_INVALID_CREDENTIALS)를
// 그대로 참조한다 — 목·컴포넌트·테스트가 같은 소스를 보게 해 하드코딩 문자열이 흩어지지 않는다.
// 전용 트리거(MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER)로 401(자격 불일치/세션 만료 2종)·400·429·
// 500(미분류) 시나리오를 재현하고, currentPassword === newPassword(신·구 동일)는 별도 트리거 없이
// 실제 값 비교로 400을 재현한다. 별도 export(mypagePasswordChangeHandler)로도 노출한다 —
// 하이브리드(VITE_ENABLE_MSW=hybrid) 개발 환경에서는 mocks/handlers.ts가 이 핸들러 하나만 제외하고
// 나머지 mypageHandlers는 그대로 둔다(effectiveReportHandlers 패턴 준용 — 다른 /me/* 엔드포인트는
// 아직 BE 병렬 미착수라 목이 계속 필요하다).
export const mypagePasswordChangeHandler = http.patch(
  '/api/users/me/password',
  async ({ request }) => {
    const { currentPassword, newPassword } = (await request.json()) as {
      currentPassword?: string;
      newPassword?: string;
    };

    if (currentPassword === MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.rateLimited) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: PASSWORD_CHANGE_ERROR_CODE.TOO_MANY_REQUESTS,
          message: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
        },
      };
      return HttpResponse.json(body, { status: 429 });
    }

    if (currentPassword === MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.wrongCurrentPassword) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: PASSWORD_CHANGE_ERROR_CODE.INVALID_CREDENTIALS,
          message: '현재 비밀번호가 일치하지 않습니다.',
        },
      };
      return HttpResponse.json(body, { status: 401 });
    }

    // 세션 만료·미인증(#1315) — "현재 비밀번호 불일치"와 status(401)는 같지만 code가 다르다. FE는
    // 이 트리거로 axios skipAuthRedirect 우회 후 자체 재로그인 유도(P2-1)를 검증한다.
    if (currentPassword === MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.sessionExpired) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: PASSWORD_CHANGE_ERROR_CODE.SESSION_EXPIRED, message: '인증이 필요합니다.' },
      };
      return HttpResponse.json(body, { status: 401 });
    }

    // 401이지만 SESSION_EXPIRED('UNAUTHORIZED')도 INVALID_CREDENTIALS도 아닌 임의 코드 — FE가
    // "화이트리스트(SESSION_EXPIRED)만 로그아웃, 그 외 401은 전부 현재 비밀번호 오류로 인라인
    // 처리"하는지 검증한다(보안 리뷰 P2-2, 분기 반전).
    if (currentPassword === MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.unknownAuthError) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'AUTH_SOME_UNMAPPED_CODE', message: '인증 오류가 발생했습니다.' },
      };
      return HttpResponse.json(body, { status: 401 });
    }

    if (currentPassword === MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.passwordNotSet) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: PASSWORD_CHANGE_ERROR_CODE.PASSWORD_NOT_SET,
          message: '소셜 로그인 전용 계정은 비밀번호를 변경할 수 없습니다.',
        },
      };
      return HttpResponse.json(body, { status: 400 });
    }

    if (newPassword !== undefined && currentPassword === newPassword) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: PASSWORD_CHANGE_ERROR_CODE.PASSWORD_UNCHANGED,
          message: '현재 비밀번호와 동일한 비밀번호로는 변경할 수 없습니다.',
        },
      };
      return HttpResponse.json(body, { status: 400 });
    }

    // 401/400/429 외 미분류 실패(CSRF 403·계정 상태 403·500·오프라인 등, P2-2) 재현용 — FE의
    // else 폴백(getApiErrorMessage) 렌더를 검증한다. 이 코드는 PASSWORD_CHANGE_ERROR_CODE에 없는
    // 임의 코드라 화이트리스트(SESSION_EXPIRED) 밖으로 떨어져 else 폴백을 그대로 검증한다.
    if (currentPassword === MYPAGE_PASSWORD_CHANGE_DEV_TRIGGER.serverError) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INTERNAL_SERVER_ERROR', message: '일시적인 오류가 발생했습니다.' },
      };
      return HttpResponse.json(body, { status: 500 });
    }

    const body: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(body);
  },
);

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
      data: { orderId, planName, listPrice: amount, credit: 0, amount, orderName },
    };
    return HttpResponse.json(body);
  }),

  // 토스페이먼츠 결제창 연동 2단계 — 결제 승인(#989, HAJA-490, #988 리뷰 픽스로 계약 확장
  // 2026-07-27). 존재하지 않는 orderId(비소유자 접근 포함)는 PAYMENT_ORDER_NOT_FOUND(404), 요청
  // amount가 주문 생성 시 금액과 다르면 PAYMENT_AMOUNT_MISMATCH(400)로 거부한다. 동일 orderId로
  // 재요청(새로고침·중복 진입)해도 결제 내역이 중복 적재되지 않고 동일한 결과를 그대로 반환한다
  // (handoff §3 멱등 요구사항 — 성공/PAYMENT_PLAN_APPLY_PENDING 둘 다 동일하게 적용).
  http.post('/api/me/payments/confirm', async ({ request }) => {
    const { paymentKey, orderId, amount } = (await request.json()) as {
      paymentKey?: string;
      orderId?: string;
      amount?: number;
    };

    const order = orderId ? mockOrders.get(orderId) : undefined;
    if (!order || !orderId) {
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

    // 이미 성공/미반영 처리된 주문의 재확인(새로고침·중복 진입)은 아래 두 분기가 최우선이다 —
    // 이 시점 이후의 "이미 그 플랜" 검사는 아직 한 번도 처리되지 않은 주문에만 적용돼야 한다
    // (이 주문이 실제로 그 플랜을 만든 장본인일 수 있어, 순서를 바꾸면 정상 재확인이 충돌로
    // 잘못 거부된다).
    if (confirmedOrderIds.has(orderId)) {
      const body: ApiResponse<MyPlan> = { success: true, data: mockMyPlanState };
      return HttpResponse.json(body);
    }
    if (pendingApplyOrderIds.has(orderId)) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'PAYMENT_PLAN_APPLY_PENDING',
          message: '결제는 완료되었습니다. 플랜 반영을 처리하고 있습니다.',
        },
      };
      return HttpResponse.json(body, { status: 409 });
    }

    // 결제(승인) 시점 플랜 중복 신청 — 이미 그 플랜이면 PG 청구 전에 거절한다(#988 리뷰 픽스).
    if (order.planName === mockMyPlanState.plan.name) {
      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'PLAN_ACTIVE_SUBSCRIPTION_CONFLICT',
          message: '이미 해당 플랜을 이용 중입니다.',
        },
      };
      return HttpResponse.json(body, { status: 409 });
    }

    // 결제 성공 + 플랜 반영 실패 시나리오(전용 트리거, MYPAGE_PAYMENT_DEV_TRIGGER.applyPending) —
    // 청구는 실제로 발생했으므로 결제 내역엔 PAID로 남기되(FE가 "결제 실패"로 오인해 재결제를
    // 유도하지 않도록), 플랜 상태(mockMyPlanState)는 갱신하지 않는다.
    if (paymentKey?.startsWith(MYPAGE_PAYMENT_DEV_TRIGGER.applyPending)) {
      pendingApplyOrderIds.add(orderId);
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

      const body: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'PAYMENT_PLAN_APPLY_PENDING',
          message: '결제는 완료되었습니다. 플랜 반영을 처리하고 있습니다.',
        },
      };
      return HttpResponse.json(body, { status: 409 });
    }

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

  // 비밀번호 변경(#1316, HAJA-602) — 별도 export(mypagePasswordChangeHandler)로도 노출한다.
  // BE #1315가 실 구현이라(핸드오프와 별개로 병렬 진행 중) 하이브리드(VITE_ENABLE_MSW=hybrid) 개발
  // 환경에서는 mocks/handlers.ts가 이 핸들러 하나만 제외하고 나머지 mypageHandlers는 그대로
  // 둔다(보안 리뷰 P2-3 — effectiveReportHandlers 패턴 준용, 다른 /me/* 엔드포인트는 아직 BE
  // 병렬 미착수라 목이 계속 필요하다).
  mypagePasswordChangeHandler,
];
