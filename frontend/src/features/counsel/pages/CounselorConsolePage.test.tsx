// @vitest-environment jsdom
// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) 통합 테스트 — 실제 useCounselorQueue 훅 + MSW
// counselHandlers. useCounselSocket은 별도 단위 테스트(useCounselSocket.test.ts)에서 이미 검증돼
// 여기서는 목으로 대체해 STOMP 프레임 시뮬레이션 없이 페이지 동작(목록→배정→채팅→종료)에 집중한다.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { counselHandlers, mockInProgressQueueTicket, mockQueueTickets } from '../api/counselApi.handlers';
import type { UseCounselSocketHandlers } from '../hooks/useCounselSocket';
import { CounselorConsolePage } from './CounselorConsolePage';

const sendMessage = vi.fn();
const sendTyping = vi.fn();
let capturedHandlers: UseCounselSocketHandlers | null = null;

vi.mock('../hooks/useCounselSocket', () => ({
  useCounselSocket: (ticketId: number | null, handlers: UseCounselSocketHandlers) => {
    capturedHandlers = handlers;
    return { connected: ticketId !== null, sendMessage, sendTyping };
  },
}));

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  sendMessage.mockClear();
  sendTyping.mockClear();
  capturedHandlers = null;
});
afterAll(() => server.close());

function renderPage(initialPath = '/counsel-console/queue') {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/counsel-console/queue" element={<CounselorConsolePage />} />
        <Route path="/counsel-console/tickets/:id" element={<CounselorConsolePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CounselorConsolePage', () => {
  it('대기열 목록을 불러와 보여준다', async () => {
    renderPage();

    expect(await screen.findByText(mockQueueTickets[0].title)).not.toBeNull();
    // 종료되지 않은 담당 상담(IN_PROGRESS)도 대기열(WAITING)과 함께 목록에 보인다(#1001 후속 버그 수정).
    expect(screen.getByText(mockInProgressQueueTicket.title)).not.toBeNull();
    expect(screen.getByText('활성 채팅 (2)')).not.toBeNull();
    expect(screen.getByText('왼쪽 목록에서 상담을 선택하세요.')).not.toBeNull();
  });

  it('목록에서 티켓 선택 → 배정받기 → 채팅창에서 메시지 송수신 → 상담 종료까지 이어진다', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));

    // 아직 배정 전(WAITING)이라 대화창 대신 배정 CTA만 보인다.
    const claimButton = await screen.findByRole('button', { name: '상담 배정받기' });
    fireEvent.click(claimButton);

    // 배정 성공 후 대화가 로드된다.
    expect(await screen.findByText('안녕하세요, 문의 주신 내용에 대해 안내드리겠습니다.')).not.toBeNull();

    const input = screen.getByPlaceholderText(
      '메시지를 입력하세요 (상담원 연결 시 활성화됩니다)',
    ) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '안내드리겠습니다' } });
    fireEvent.click(screen.getByRole('button', { name: '전송' }));

    expect(sendMessage).toHaveBeenCalledWith('안내드리겠습니다');
    expect(input.value).toBe('');

    fireEvent.click(screen.getByRole('button', { name: '상담 종료' }));

    await waitFor(() =>
      expect(screen.getByText('왼쪽 목록에서 상담을 선택하세요.')).not.toBeNull(),
    );
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

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));
    fireEvent.click(await screen.findByRole('button', { name: '상담 배정받기' }));

    expect(await screen.findByRole('alert')).not.toBeNull();
    expect(screen.getByText('이미 상담 세션이 배정된 티켓입니다.')).not.toBeNull();
  });

  it('소켓 onEnded 콜백(다른 경로로 상담 종료)을 받으면 대기열 화면으로 돌아간다', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockQueueTickets[0].title));
    fireEvent.click(await screen.findByRole('button', { name: '상담 배정받기' }));
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

    await waitFor(() =>
      expect(screen.getByText('왼쪽 목록에서 상담을 선택하세요.')).not.toBeNull(),
    );
  });
});
