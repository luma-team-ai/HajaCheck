// @vitest-environment jsdom
// 플랫폼 관리자 상담 관리(#1168) — PlatformAdminCounselsPage 3-컬럼 통합 스모크 테스트.
// 실제 react-query + MSW(counselHandlers)로 날짜별 목록 → 티켓 선택 → 트랜스크립트/정보 패널까지
// 엔드투엔드로 조합되는지 확인한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { counselHandlers, mockAdminTickets } from '../api/counselApi.handlers';
import { PlatformAdminCounselsPage } from './PlatformAdminCounselsPage';

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PlatformAdminCounselsPage />
    </QueryClientProvider>,
  );
}

describe('PlatformAdminCounselsPage', () => {
  it('오늘 날짜로 초기 조회 후, 날짜를 바꾸면 해당 날짜의 티켓 목록을 보여준다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('날짜 검색'), { target: { value: '2026-07-28' } });

    // 1. 기본적으로 '상담 대기'가 열려 있으므로 '이고객'(WAITING)이 먼저 노출됩니다.
    expect(await screen.findByText(mockAdminTickets[1].customerName as string)).toBeTruthy();

    // 2. '종료' 아코디언 그룹을 클릭하여 펼칩니다 (이때 '상담 대기'는 접히고 '종료'만 열립니다)
    fireEvent.click(await screen.findByText(/종료 \(/));

    // 3. '종료' 그룹이 열렸으므로 '박고객'(RESOLVED)이 노출됩니다.
    expect(await screen.findByText(mockAdminTickets[0].customerName as string)).toBeTruthy();
  });

  it('티켓을 선택하면 트랜스크립트와 정보 패널이 함께 채워진다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('날짜 검색'), { target: { value: '2026-07-28' } });

    // '종료' 아코디언 그룹을 클릭하여 펼칩니다
    fireEvent.click(await screen.findByText(/종료 \(/));

    fireEvent.click(await screen.findByText(mockAdminTickets[0].customerName as string));

    expect(await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.')).toBeTruthy();
    expect(screen.getByText(mockAdminTickets[0].customerEmail as string)).toBeTruthy();
  });
});
