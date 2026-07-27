// @vitest-environment jsdom
// 상담원 콘솔 > 채팅(#1001, HAJA-495) 통합 테스트 — MSW로 대화 조회/종료 API를 채우고,
// useCounselSocket은 별도 단위 테스트(useCounselSocket.test.ts)에서 이미 검증됐으므로 여기서는
// 목으로 대체해 STOMP 프레임 시뮬레이션 없이 페이지 동작(렌더/전송/종료)에 집중한다.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { counselHandlers } from '../api/counselApi.handlers';
import type { UseCounselSocketHandlers } from '../hooks/useCounselSocket';
import { CounselorChatPage } from './CounselorChatPage';

const sendMessage = vi.fn();
let capturedHandlers: UseCounselSocketHandlers | null = null;

vi.mock('../hooks/useCounselSocket', () => ({
  useCounselSocket: (_ticketId: number | null, handlers: UseCounselSocketHandlers) => {
    capturedHandlers = handlers;
    return { connected: true, sendMessage };
  },
}));

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  sendMessage.mockClear();
  capturedHandlers = null;
});
afterAll(() => server.close());

function renderPage(initialPath = '/counsel-console/tickets/3') {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/counsel-console/tickets/:id" element={<CounselorChatPage />} />
        <Route path="/counsel-console/queue" element={<div>대기열 페이지</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CounselorChatPage', () => {
  it('대화 목록을 불러와 보여주고 연결 상태를 노출한다', async () => {
    renderPage();

    expect(await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.')).not.toBeNull();
    expect(screen.getByText('연결됨')).not.toBeNull();
  });

  it('메시지 입력 후 전송하면 sendMessage가 호출되고 입력창이 비워진다', async () => {
    renderPage();
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    const input = screen.getByPlaceholderText('메시지를 입력하세요') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '안내드리겠습니다' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    expect(sendMessage).toHaveBeenCalledWith('안내드리겠습니다');
    expect(input.value).toBe('');
  });

  it('상담 종료 버튼 클릭 시 resolve API 호출 후 대기열로 이동한다', async () => {
    renderPage();
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    fireEvent.click(screen.getByRole('button', { name: '상담 종료' }));

    expect(await screen.findByText('대기열 페이지')).not.toBeNull();
  });

  it('소켓 onEnded 콜백이 오면 대기열로 이동한다', async () => {
    renderPage();
    await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.');

    capturedHandlers?.onEnded?.({
      id: 3,
      ticketNumber: 'CS-20260727-001',
      category: '점검 결과서 관련',
      title: 'AI 분석 결과 등급 문의',
      userId: 200,
      counselorId: 9,
      status: 'RESOLVED',
      queuePosition: null,
      createdAt: '2026-07-27T09:00:00',
    });

    await waitFor(() => expect(screen.getByText('대기열 페이지')).not.toBeNull());
  });
});
