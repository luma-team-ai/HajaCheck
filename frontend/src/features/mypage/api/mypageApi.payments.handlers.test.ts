// @vitest-environment jsdom
// 토스페이먼츠 결제창 연동(#989, HAJA-490) — MSW 핸들러(mypageApi.handlers.ts) 자체의 계약 검증.
// adminApi.handlers.test.ts와 동일 패턴(raw fetch로 핸들러를 직접 때린다) — 주문 생성/결제 승인
// 2단계와 handoff §3 멱등 요구사항, §"API 계약"의 PAYMENT_ORDER_NOT_FOUND/PAYMENT_AMOUNT_MISMATCH를
// 컴포넌트 레이어 없이 결정적으로 검증한다.
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { MyPlan, PaymentHistoryItem, PlanOrder } from '../types';
import { MYPAGE_PAYMENT_DEV_TRIGGER, mypageHandlers, resetMypagePaymentMockStore } from './mypageApi.handlers';

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // 모듈 스코프 상태(mockMyPlanState 등)는 resetHandlers()로 초기화되지 않는다 — 특히 아래 "이미
  // 그 플랜" 충돌 검사가 현재 mockMyPlanState에 의존해, 리셋하지 않으면 이전 it()이 바꾼 플랜이
  // 이후 테스트의 "정상 승인" 기대와 실행 순서에 따라 충돌한다(mypageApi.handlers.ts 참고).
  resetMypagePaymentMockStore();
});
afterAll(() => server.close());

async function createOrder(planName: string) {
  const res = await fetch('/api/me/plan/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ planName }),
  });
  const body = (await res.json()) as ApiResponse<PlanOrder>;
  return { status: res.status, body };
}

async function confirmPayment(payload: { paymentKey: string; orderId: string; amount: number }) {
  const res = await fetch('/api/me/payments/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const body = (await res.json()) as ApiResponse<MyPlan>;
  return { status: res.status, body };
}

async function getPayments() {
  const res = await fetch('/api/me/payments');
  const body = (await res.json()) as ApiResponse<{ payments: PaymentHistoryItem[] }>;
  return body.data.payments;
}

describe('POST /api/me/plan/orders', () => {
  it('STANDARD/ENTERPRISE는 orderId·amount·orderName을 발급한다', async () => {
    const { status, body } = await createOrder('STANDARD');

    expect(status).toBe(200);
    expect(body.success).toBe(true);
    expect(body.data?.planName).toBe('STANDARD');
    expect(body.data?.amount).toBe(29000);
    expect(body.data?.orderId).toMatch(/^order_mock_/);
  });

  it('대상 외 플랜(FREE)은 400 INVALID_INPUT으로 거부한다', async () => {
    const { status, body } = await createOrder('FREE');

    expect(status).toBe(400);
    expect(body.success).toBe(false);
    expect(body.error?.code).toBe('INVALID_INPUT');
  });
});

describe('POST /api/me/payments/confirm', () => {
  it('존재하지 않는 orderId는 404 PAYMENT_ORDER_NOT_FOUND를 반환한다', async () => {
    const { status, body } = await confirmPayment({
      paymentKey: 'pay_1',
      orderId: 'order_does_not_exist',
      amount: 29000,
    });

    expect(status).toBe(404);
    expect(body.error?.code).toBe('PAYMENT_ORDER_NOT_FOUND');
  });

  it('주문 생성 시 금액과 다르면 400 PAYMENT_AMOUNT_MISMATCH를 반환한다', async () => {
    const { body: orderBody } = await createOrder('STANDARD');
    const orderId = orderBody.data!.orderId;

    const { status, body } = await confirmPayment({
      paymentKey: 'pay_mismatch',
      orderId,
      amount: 999,
    });

    expect(status).toBe(400);
    expect(body.error?.code).toBe('PAYMENT_AMOUNT_MISMATCH');
  });

  it('정상 승인 시 결제 내역에 반영되고 갱신된 MyPlan을 반환한다', async () => {
    const { body: orderBody } = await createOrder('ENTERPRISE');
    const orderId = orderBody.data!.orderId;

    const { status, body } = await confirmPayment({
      paymentKey: 'pay_ok',
      orderId,
      amount: 59000,
    });

    expect(status).toBe(200);
    expect(body.data?.plan.name).toBe('ENTERPRISE');
    expect(body.data?.plan.priceMonthly).toBe(59000);

    const payments = await getPayments();
    expect(payments[0]).toMatchObject({ orderId, planName: 'ENTERPRISE', amount: 59000, status: 'PAID' });
  });

  // 새로고침·중복 진입 대비(handoff §3) — 백엔드가 멱등 처리해 동일 orderId 재확인 요청도 200으로
  // 같은 결과를 돌려주고, "이미 결제되었습니다" 류 오류로 막지 않는다. 결제 내역도 중복 적재되지 않는다.
  // 초기 mock 플랜이 STANDARD라 STANDARD 주문을 쓰면 "이미 그 플랜" 충돌과 겹치므로 ENTERPRISE로 검증한다.
  it('동일 orderId로 재확인해도(새로고침) 200으로 동일 결과를 반환하고 내역이 중복 적재되지 않는다', async () => {
    const { body: orderBody } = await createOrder('ENTERPRISE');
    const orderId = orderBody.data!.orderId;
    const payload = { paymentKey: 'pay_idempotent', orderId, amount: 59000 };

    const first = await confirmPayment(payload);
    const second = await confirmPayment(payload);

    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect(second.body.error).toBeUndefined();

    const payments = await getPayments();
    const matching = payments.filter((payment) => payment.orderId === orderId);
    expect(matching).toHaveLength(1);
  });

  // #988 백엔드 리뷰 픽스(2026-07-27) — 이미 그 플랜인데 확정을 시도하면 PG 청구 전에 거절된다.
  it('승인 시점에 이미 그 플랜이면 PG 청구 전에 409 PLAN_ACTIVE_SUBSCRIPTION_CONFLICT로 거절한다', async () => {
    // 먼저 ENTERPRISE로 전환해 "이미 그 플랜"인 상태를 만든다.
    const setupOrder = await createOrder('ENTERPRISE');
    const setupOrderId = setupOrder.body.data!.orderId;
    const setupConfirm = await confirmPayment({
      paymentKey: 'pay_conflict_setup',
      orderId: setupOrderId,
      amount: 59000,
    });
    expect(setupConfirm.status).toBe(200);
    expect(setupConfirm.body.data?.plan.name).toBe('ENTERPRISE');

    const paymentsBefore = await getPayments();

    // 이미 ENTERPRISE인 상태에서 ENTERPRISE 주문을 또 확정하려 하면 청구 전에 거절돼야 한다
    // (setupOrderId와 다른 별도 주문 — 동일 주문 재확인의 멱등 성공 경로와 구분).
    const conflictOrder = await createOrder('ENTERPRISE');
    const conflictOrderId = conflictOrder.body.data!.orderId;
    const { status, body } = await confirmPayment({
      paymentKey: 'pay_conflict',
      orderId: conflictOrderId,
      amount: 59000,
    });

    expect(status).toBe(409);
    expect(body.error?.code).toBe('PLAN_ACTIVE_SUBSCRIPTION_CONFLICT');

    // 청구 전에 거절됐으므로 결제 내역에 새 레코드가 추가되지 않는다(중복 청구 없음).
    const paymentsAfter = await getPayments();
    expect(paymentsAfter).toHaveLength(paymentsBefore.length);
  });

  // #988 백엔드 리뷰 픽스(2026-07-27) — 결제(PG 승인)는 성공했지만 플랜 반영만 실패한 상태.
  // "결제 실패"로 취급되면 안 된다: 결제 내역엔 PAID로 남지만(청구는 실제로 발생) 플랜은
  // 갱신되지 않고, 같은 orderId로 재확인해도 매번 같은 코드를 반환한다(성공으로 뒤바뀌지 않음
  // — 재결제를 유도하면 환불 불가한 중복 청구가 된다).
  it('전용 트리거로 PAYMENT_PLAN_APPLY_PENDING을 재현하면 결제 내역엔 남지만 플랜은 갱신되지 않는다', async () => {
    const { body: orderBody } = await createOrder('ENTERPRISE');
    const order = orderBody.data!;
    const paymentKey = `${MYPAGE_PAYMENT_DEV_TRIGGER.applyPending}pay_1`;

    const { status, body } = await confirmPayment({
      paymentKey,
      orderId: order.orderId,
      amount: order.amount,
    });

    expect(status).toBe(409);
    expect(body.error?.code).toBe('PAYMENT_PLAN_APPLY_PENDING');

    const planRes = await fetch('/api/me/plan');
    const planBody = (await planRes.json()) as ApiResponse<MyPlan>;
    expect(planBody.data?.plan.name).toBe('STANDARD'); // 초기 mock 플랜 그대로 — 반영 안 됨

    const payments = await getPayments();
    expect(payments.find((p) => p.orderId === order.orderId)).toMatchObject({
      status: 'PAID', // 청구는 실제로 발생
      planName: 'ENTERPRISE',
      amount: 59000,
    });

    // 동일 orderId로 재확인(새로고침)해도 성공으로 뒤바뀌지 않고 같은 코드를 반환한다.
    const retry = await confirmPayment({ paymentKey, orderId: order.orderId, amount: order.amount });
    expect(retry.status).toBe(409);
    expect(retry.body.error?.code).toBe('PAYMENT_PLAN_APPLY_PENDING');

    // 재확인해도 결제 내역이 중복 적재되지 않는다.
    const paymentsAfterRetry = await getPayments();
    expect(paymentsAfterRetry.filter((p) => p.orderId === order.orderId)).toHaveLength(1);
  });
});
