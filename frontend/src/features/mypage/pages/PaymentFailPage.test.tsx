// @vitest-environment jsdom
// 토스페이먼츠 결제창 연동(#989, HAJA-490) — failUrl 착지점. 완료 기준 "결제 실패" 케이스.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { PaymentFailPage } from './PaymentFailPage';

function renderPage(search: string) {
  return render(
    <MemoryRouter initialEntries={[`/payments/fail${search}`]}>
      <Routes>
        <Route path="/payments/fail" element={<PaymentFailPage />} />
        <Route path="/mypage/plan" element={<div>내 플랜 화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => cleanup());

describe('PaymentFailPage', () => {
  it('알려진 토스 실패 코드는 매핑된 안내 문구를 보여준다', () => {
    renderPage('?code=PAY_PROCESS_CANCELED&message=raw&orderId=order_mock_1');
    expect(screen.getByText('결제를 취소했습니다.')).toBeTruthy();
  });

  it('알 수 없는 코드는 결제창이 내려준 message를 그대로 보여준다', () => {
    renderPage('?code=UNKNOWN_CODE&message=카드 한도를 초과했습니다.&orderId=order_mock_1');
    expect(screen.getByText('카드 한도를 초과했습니다.')).toBeTruthy();
  });

  it('code/message가 모두 없으면 기본 안내 문구를 보여준다', () => {
    renderPage('');
    expect(screen.getByText('결제가 완료되지 않았습니다.')).toBeTruthy();
  });

  it('"내 플랜으로 돌아가기" 클릭 시 /mypage/plan으로 이동한다', () => {
    renderPage('?code=PAY_PROCESS_CANCELED');
    fireEvent.click(screen.getByRole('button', { name: '내 플랜으로 돌아가기' }));
    expect(screen.getByText('내 플랜 화면')).toBeTruthy();
  });
});
