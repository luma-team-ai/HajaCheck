// @vitest-environment jsdom
// 고객측 실시간 채팅 전환(#1000, HAJA-494) — useCounselHistory의 소켓 연동 로직 단위 테스트.
// useCounselSocket은 실제 STOMP/WebSocket 연결 대신 mock으로 대체해, 훅이 handlers를 올바르게
// 호출해 messages/allTickets 상태를 갱신하는지만 검증한다(연결 자체는 useCounselSocket.test.ts가 담당).
import { act, renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { counselHandlers, mockMessages, mockTickets } from '../api/counselApi.handlers';
import type { UseCounselSocketHandlers } from './useCounselSocket';
import { useCounselHistory } from './useCounselHistory';

let capturedTicketId: number | null = null;
let capturedHandlers: UseCounselSocketHandlers | null = null;
const sendMessageMock = vi.fn();

vi.mock('./useCounselSocket', () => ({
  useCounselSocket: (ticketId: number | null, handlers: UseCounselSocketHandlers) => {
    capturedTicketId = ticketId;
    capturedHandlers = handlers;
    return { connected: true, sendMessage: sendMessageMock };
  },
}));

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  capturedTicketId = null;
  capturedHandlers = null;
  sendMessageMock.mockClear();
});
afterAll(() => server.close());

describe('useCounselHistory 소켓 연동', () => {
  it('선택된 티켓이 IN_PROGRESS면 해당 ticketId로 소켓을 연결한다', async () => {
    const { result } = renderHook(() => useCounselHistory());

    await waitFor(() => expect(result.current.selectedTicket?.id).toBe(mockTickets[0].id));
    expect(mockTickets[0].status).toBe('IN_PROGRESS');
    expect(capturedTicketId).toBe(mockTickets[0].id);
  });

  it('onMessage 수신 시 id 기준으로 dedupe해 messages에 append한다', async () => {
    const { result } = renderHook(() => useCounselHistory());
    await waitFor(() => expect(result.current.messages.length).toBe(mockMessages.length));

    const existingId = mockMessages[0].id;
    act(() => {
      capturedHandlers?.onMessage({
        id: existingId,
        sessionId: 700,
        sender: 'USER',
        content: '중복 메시지(무시되어야 함)',
        attachmentUrl: null,
        createdAt: new Date().toISOString(),
      });
    });
    expect(result.current.messages.length).toBe(mockMessages.length);

    act(() => {
      capturedHandlers?.onMessage({
        id: 9999,
        sessionId: 700,
        sender: 'COUNSELOR',
        content: '실시간 새 메시지',
        attachmentUrl: null,
        createdAt: new Date().toISOString(),
      });
    });
    expect(result.current.messages.length).toBe(mockMessages.length + 1);
    expect(result.current.messages.at(-1)?.content).toBe('실시간 새 메시지');
  });

  it('onAssigned/onEnded 수신 시 해당 티켓 상태를 갱신한다', async () => {
    const { result } = renderHook(() => useCounselHistory());
    await waitFor(() => expect(result.current.tickets.length).toBe(mockTickets.length));

    const ended = { ...mockTickets[0], status: 'RESOLVED' as const };
    act(() => {
      capturedHandlers?.onEnded?.(ended);
    });

    await waitFor(() => {
      const updated = result.current.tickets.find((t) => t.id === ended.id);
      expect(updated?.status).toBe('RESOLVED');
    });
  });

  it('sendMessage를 그대로 노출한다', async () => {
    const { result } = renderHook(() => useCounselHistory());
    await waitFor(() => expect(result.current.selectedTicket).not.toBeNull());

    act(() => {
      result.current.sendMessage('안녕하세요');
    });

    expect(sendMessageMock).toHaveBeenCalledWith('안녕하세요');
  });
});
