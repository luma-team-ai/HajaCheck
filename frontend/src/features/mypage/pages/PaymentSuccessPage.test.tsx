// @vitest-environment jsdom
// 토스페이먼츠 결제창 연동(#989, HAJA-490) — successUrl 착지점. 완료 기준의 "결제 성공 /
// success 라우트 새로고침(멱등) / 금액 불일치(400)" 3종을 이 파일에서 검증한다
// (PAYMENT_ORDER_NOT_FOUND/PAYMENT_AMOUNT_MISMATCH 자체 계약은 mypageApi.payments.handlers.test.ts가
// 이미 API 레벨로 검증하므로, 여기서는 페이지가 그 응답을 올바르게 표시/전이하는지에 집중한다).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { mypageHandlers } from '../api/mypageApi.handlers';
import type { PlanOrder } from '../types';
import { PaymentSuccessPage } from './PaymentSuccessPage';

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
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

  it('결제 승인 성공 시 내 플랜 화면으로 이동한다', async () => {
    const order = await createOrder('STANDARD');
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
});
