// @vitest-environment jsdom
// 토스페이먼츠 결제창 연동(#989, HAJA-490) — successUrl 착지점. 완료 기준의 "결제 성공 /
// success 라우트 새로고침(멱등) / 금액 불일치(400)" 3종에 더해, 백엔드(#988) 리뷰 픽스로 추가된
// PAYMENT_PLAN_APPLY_PENDING(결제 성공+플랜 반영 대기)·PLAN_ACTIVE_SUBSCRIPTION_CONFLICT(승인
// 시점 중복 신청)·비소유자 접근 403→404 변경을 이 파일에서 검증한다
// (PAYMENT_ORDER_NOT_FOUND/PAYMENT_AMOUNT_MISMATCH 자체 계약은 mypageApi.payments.handlers.test.ts가
// 이미 API 레벨로 검증하므로, 여기서는 페이지가 그 응답을 올바르게 표시/전이하는지에 집중한다).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import {
  MYPAGE_PAYMENT_DEV_TRIGGER,
  mypageHandlers,
  resetMypagePaymentMockStore,
} from '../api/mypageApi.handlers';
import type { PlanOrder } from '../types';
import { PaymentSuccessPage } from './PaymentSuccessPage';

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // 모듈 스코프 상태(mockMyPlanState 등)는 resetHandlers()로 초기화되지 않는다 — 이 파일의 여러
  // it()이 같은 mypageApi.handlers.ts 인스턴스를 공유하므로, "이미 그 플랜" 충돌 검사가 이전
  // 테스트가 바꾼 플랜에 영향받지 않도록 매 테스트 시작 전 초기 mock 상태(STANDARD)로 되돌린다.
  resetMypagePaymentMockStore();
  cleanup();
});
afterAll(() => server.close());

async function createOrder(planName: string): Promise<PlanOrder> {
  const res = await fetch('/api/me/plan/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ planName }),
  });
  const body = (await res.json()) as ApiResponse<PlanOrder>;
  return body.data as PlanOrder;
}

function renderPage(search: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/payments/success${search}`]}>
        <Routes>
          <Route path="/payments/success" element={<PaymentSuccessPage />} />
          <Route path="/mypage/plan" element={<div>내 플랜 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PaymentSuccessPage', () => {
  it('쿼리 파라미터가 없으면 잘못된 접근 안내를 보여준다(결제 승인 API 호출 없음)', async () => {
    renderPage('');
    expect(
      await screen.findByText('잘못된 접근입니다. 결제 정보를 확인할 수 없습니다.'),
    ).toBeTruthy();
  });

  // 초기 mock 플랜이 STANDARD라 STANDARD 주문을 승인하면 "이미 그 플랜" 충돌과 겹치므로
  // ENTERPRISE(업그레이드)로 검증한다.
  it('결제 승인 성공 시 내 플랜 화면으로 이동한다', async () => {
    const order = await createOrder('ENTERPRISE');
    renderPage(`?paymentKey=pay_1&orderId=${order.orderId}&amount=${order.amount}`);

    await screen.findByText('내 플랜 화면');
  });

  it('금액이 일치하지 않으면(PAYMENT_AMOUNT_MISMATCH, 400) 오류와 복귀 버튼을 보여준다', async () => {
    const order = await createOrder('STANDARD');
    renderPage(`?paymentKey=pay_2&orderId=${order.orderId}&amount=1`);

    expect(await screen.findByText('결제 금액이 일치하지 않습니다.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '내 플랜으로 돌아가기' })).toBeTruthy();
  });

  // 새로고침·중복 진입 대비(handoff §3) — 동일 orderId로 재진입해도 "이미 결제되었습니다" 류
  // 오인 문구 없이 그대로 성공 처리되어 내 플랜 화면으로 이동해야 한다.
  it('새로고침처럼 동일 쿼리로 재진입해도(멱등) 정상적으로 내 플랜 화면으로 이동한다', async () => {
    const order = await createOrder('ENTERPRISE');
    const search = `?paymentKey=pay_refresh&orderId=${order.orderId}&amount=${order.amount}`;

    const first = renderPage(search);
    await screen.findByText('내 플랜 화면');
    first.unmount();

    renderPage(search);
    await screen.findByText('내 플랜 화면');
  });

  // 비소유자 주문 접근이 403(PAYMENT_FORBIDDEN) → 404(PAYMENT_ORDER_NOT_FOUND)로 바뀌었다(#988
  // 리뷰 픽스) — 404 분기가 없어 빈 화면이 되지 않는지 확인한다(코드 리뷰 지적 항목).
  it('존재하지 않는(또는 비소유자) 주문은 404 PAYMENT_ORDER_NOT_FOUND 메시지를 보여준다(빈 화면 아님)', async () => {
    renderPage('?paymentKey=pay_404&orderId=order_not_mine&amount=29000');

    expect(await screen.findByText('결제 주문 정보를 찾을 수 없습니다.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '내 플랜으로 돌아가기' })).toBeTruthy();
  });

  // #988 백엔드 리뷰 픽스(2026-07-27) — PAYMENT_PLAN_APPLY_PENDING은 "결제 실패"가 아니다.
  // danger 스타일(role=alert)도, 재시도/재결제 유도 버튼도 없어야 한다.
  it('PAYMENT_PLAN_APPLY_PENDING이면 결제 완료 + 플랜 반영 중 안내만 보여주고 재결제를 유도하지 않는다', async () => {
    const order = await createOrder('ENTERPRISE');
    const paymentKey = `${MYPAGE_PAYMENT_DEV_TRIGGER.applyPending}pay_pending`;
    renderPage(`?paymentKey=${paymentKey}&orderId=${order.orderId}&amount=${order.amount}`);

    // LoadingSpinner도 role="status"라 findByRole('status')는 로딩 중 문구를 먼저 잡을 수 있다 —
    // 확인 문구가 뜰 때까지 텍스트로 직접 기다린다.
    const message = await screen.findByText(/결제는 완료되었습니다/);
    expect(message.textContent).toContain('플랜 반영을 처리하고 있습니다');
    expect(message.getAttribute('role')).toBe('status');

    // "실패"로 읽히는 danger 알림이 없어야 한다.
    expect(screen.queryByRole('alert')).toBeNull();
    // 재시도/재결제를 뜻하는 문구·버튼이 없어야 한다("확인하기" 성격의 내비게이션만 허용).
    expect(screen.queryByRole('button', { name: /다시 시도|재결제|재시도/ })).toBeNull();
    expect(screen.getByRole('button', { name: '내 플랜에서 확인하기' })).toBeTruthy();

    // 자동으로 내 플랜 화면으로 이동하지 않는다(사용자가 안내를 읽을 시간을 준다).
    expect(screen.queryByText('내 플랜 화면')).toBeNull();
  });

  // #988 백엔드 리뷰 픽스 — 승인 시점에 이미 그 플랜이면 PG 청구 전에 거절된다. "결제 실패"가
  // 아니라 "이미 이용 중" 안내 + 새로고침 유도로 보여줘야 한다.
  it('PLAN_ACTIVE_SUBSCRIPTION_CONFLICT면 이미 해당 플랜을 이용 중이라는 안내를 보여준다', async () => {
    // 먼저 ENTERPRISE로 전환해 "이미 그 플랜"인 상태를 만든다.
    const setupOrder = await createOrder('ENTERPRISE');
    const setupSearch = `?paymentKey=pay_setup&orderId=${setupOrder.orderId}&amount=${setupOrder.amount}`;
    const setupRender = renderPage(setupSearch);
    await screen.findByText('내 플랜 화면');
    setupRender.unmount();

    // 이미 ENTERPRISE인 상태에서 별도의 ENTERPRISE 주문을 확정하면 충돌로 거절된다.
    const conflictOrder = await createOrder('ENTERPRISE');
    renderPage(`?paymentKey=pay_conflict&orderId=${conflictOrder.orderId}&amount=${conflictOrder.amount}`);

    const message = await screen.findByText(/이미 해당 플랜을 이용 중입니다/);
    expect(message.getAttribute('role')).toBe('status');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
