// @vitest-environment jsdom
// 마이페이지 > 내 상담 이력(#20, HAJA-33) 통합 테스트 — 실제 useCounselHistory 훅 + MSW counselHandlers.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { counselHandlers, mockTickets } from '../api/counselApi.handlers';
import { CounselHistoryPage } from './CounselHistoryPage';

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  render(
    <MemoryRouter>
      <CounselHistoryPage />
    </MemoryRouter>,
  );
}

describe('CounselHistoryPage', () => {
  it('목록을 불러와 첫 번째 티켓을 자동 선택하고 대화를 보여준다', async () => {
    renderPage();

    expect(
      await screen.findByRole('heading', { name: mockTickets[0].title }, { timeout: 3000 }),
    ).toBeTruthy();
    expect(await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.')).toBeTruthy();
    expect(screen.getByText(`#${mockTickets[0].ticketNumber}`)).toBeTruthy();
  });

  it('다른 티켓 카드를 클릭하면 해당 대화로 전환된다', async () => {
    renderPage();

    await screen.findByRole('heading', { name: mockTickets[0].title });
    fireEvent.click(screen.getByRole('button', { name: new RegExp(mockTickets[1].title) }));

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: mockTickets[1].title })).toBeTruthy();
    });
  });

  it('목록 조회 실패 시 에러 메시지를 노출한다', async () => {
    server.use(
      http.get('/api/counsel/tickets/mine', () =>
        HttpResponse.json(
          { success: false, error: { code: 'INTERNAL_ERROR', message: '상담 이력을 불러오지 못했습니다.' } },
          { status: 500 },
        ),
      ),
    );

    renderPage();

    expect(await screen.findByText('상담 이력을 불러오지 못했습니다.')).toBeTruthy();
  });

  it('상담 이력이 없으면 새 상담 시작 버튼을 보여준다', async () => {
    server.use(
      http.get('/api/counsel/tickets/mine', () =>
        HttpResponse.json({ success: true, data: { content: [], page: 0, totalElements: 0 } }),
      ),
    );

    renderPage();

    expect(await screen.findByRole('button', { name: '새 상담 시작' })).toBeTruthy();
  });
});
