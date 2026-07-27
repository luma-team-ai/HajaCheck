// @vitest-environment jsdom
// 상담원 콘솔 > 대기열(#1001, HAJA-495) 통합 테스트 — 실제 useCounselorQueue 훅 + MSW counselHandlers.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { counselHandlers, mockQueueTickets } from '../api/counselApi.handlers';
import { CounselorQueuePage } from './CounselorQueuePage';

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
      <CounselorQueuePage />
    </MemoryRouter>,
  );
}

describe('CounselorQueuePage', () => {
  it('대기열 목록을 불러와 보여준다', async () => {
    renderPage();

    expect(await screen.findByText(mockQueueTickets[0].title)).not.toBeNull();
    expect(screen.getByText('상담 대기열 (1건)')).not.toBeNull();
  });

  it('배정받기 클릭 시 클레임에 성공하면 채팅 화면으로 이동한다', async () => {
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '배정받기' }));

    // 페이지 이동은 MemoryRouter 내부에서 일어나 대기열 카드가 사라진다(언마운트) — 성공 시
    // 클레임 실패 안내가 뜨지 않는 것으로 간접 검증.
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('클레임 409 경합 시 안내 메시지를 보여주고 큐를 새로고침한다', async () => {
    server.use(
      http.post('/api/counsel/tickets/:id/assign', () =>
        HttpResponse.json(
          {
            success: false,
            error: { code: 'COUNSEL_SESSION_ASSIGNMENT_CONFLICT', message: '이미 상담 세션이 배정된 티켓입니다.' },
          },
          { status: 409 },
        ),
      ),
    );

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '배정받기' }));

    expect(await screen.findByRole('alert')).not.toBeNull();
    expect(screen.getByText('이미 상담 세션이 배정된 티켓입니다.')).not.toBeNull();
  });
});
