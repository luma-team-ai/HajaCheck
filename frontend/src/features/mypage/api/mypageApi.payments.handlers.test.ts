// @vitest-environment jsdom
// 토스페이먼츠 결제창 연동(#989, HAJA-490) — MSW 핸들러(mypageApi.handlers.ts) 자체의 계약 검증.
// adminApi.handlers.test.ts와 동일 패턴(raw fetch로 핸들러를 직접 때린다) — 주문 생성/결제 승인
// 2단계와 handoff §3 멱등 요구사항, §"API 계약"의 PAYMENT_ORDER_NOT_FOUND/PAYMENT_AMOUNT_MISMATCH를
// 컴포넌트 레이어 없이 결정적으로 검증한다.
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type { MyPlan, PaymentHistoryItem, PlanOrder } from '../types';
import { mypageHandlers } from './mypageApi.handlers';

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
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
  it('동일 orderId로 재확인해도(새로고침) 200으로 동일 결과를 반환하고 내역이 중복 적재되지 않는다', async () => {
    const { body: orderBody } = await createOrder('STANDARD');
    const orderId = orderBody.data!.orderId;
    const payload = { paymentKey: 'pay_idempotent', orderId, amount: 29000 };

    const first = await confirmPayment(payload);
    const second = await confirmPayment(payload);

    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect(second.body.error).toBeUndefined();

    const payments = await getPayments();
    const matching = payments.filter((payment) => payment.orderId === orderId);
    expect(matching).toHaveLength(1);
  });
});
