// @vitest-environment jsdom
// useCounselSocket 단위 테스트(#999, HAJA-493) — 실제 서버 없이 mock WebSocket으로
// STOMP 연결/구독/발행/cleanup 동작을 검증한다. @stomp/stompjs가 webSocketFactory로 받은
// 객체에 기대하는 최소 인터페이스(readyState/onopen/onmessage/onclose/send/close)만 구현한다.
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChatMessageResponse, CounselTicketSummaryResponse } from '../types';
import { useCounselSocket } from './useCounselSocket';

const OPEN = 1;
const CLOSED = 3;

class MockWebSocket {
  static instances: MockWebSocket[] = [];

  url: string;
  readyState = 0; // CONNECTING
  onopen: (() => void) | null = null;
  onmessage: ((evt: { data: string }) => void) | null = null;
  onclose: ((evt: { code: number; reason: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  sent: string[] = [];
  closed = false;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    this.closed = true;
    this.readyState = CLOSED;
    this.onclose?.({ code: 1000, reason: '' });
  }

  // 테스트 헬퍼 — 실제 소켓이 서버 CONNECTED 프레임을 받은 것처럼 시뮬레이션
  simulateOpen() {
    this.readyState = OPEN;
    this.onopen?.();
  }

  simulateFrame(raw: string) {
    this.onmessage?.({ data: raw });
  }
}

function connectedFrame() {
  return 'CONNECTED\nversion:1.2\n\n\0';
}

function errorFrame(message: string) {
  return `ERROR\nmessage:${message}\n\n\0`;
}

function messageFrame(destination: string, subscriptionId: string, body: unknown) {
  return `MESSAGE\ndestination:${destination}\nsubscription:${subscriptionId}\nmessage-id:1\ncontent-type:application/json\n\n${JSON.stringify(
    body,
  )}\0`;
}

// SUBSCRIBE 프레임에서 subscription id를 뽑아 destination과 매칭한다(테스트 전용 파서).
function findSubscriptionId(ws: MockWebSocket, destination: string): string {
  for (const frame of ws.sent) {
    if (!frame.startsWith('SUBSCRIBE')) continue;
    if (!frame.includes(`destination:${destination}`)) continue;
    const match = /id:([^\n]+)/.exec(frame);
    if (match) return match[1];
  }
  throw new Error(`subscribe frame not found for ${destination}`);
}

async function openAndConnect(index = 0) {
  await waitFor(() => expect(MockWebSocket.instances.length).toBeGreaterThan(index));
  const ws = MockWebSocket.instances[index];
  act(() => {
    ws.simulateOpen();
  });
  await waitFor(() => {
    expect(ws.sent.some((f) => f.startsWith('CONNECT'))).toBe(true);
  });
  act(() => {
    ws.simulateFrame(connectedFrame());
  });
  return ws;
}

describe('useCounselSocket', () => {
  const originalWebSocket = globalThis.WebSocket;

  afterEach(() => {
    MockWebSocket.instances = [];
    globalThis.WebSocket = originalWebSocket;
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('ticketId가 없으면 연결하지 않는다', () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    const { result } = renderHook(() => useCounselSocket(null, { onMessage }));

    expect(result.current.connected).toBe(false);
    expect(MockWebSocket.instances).toHaveLength(0);
  });

  it('ticketId가 있으면 연결 후 topic/개인 큐를 구독하고 connected=true가 된다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();
    const onAssigned = vi.fn();
    const onEnded = vi.fn();

    const { result } = renderHook(() => useCounselSocket(42, { onMessage, onAssigned, onEnded }));

    const ws = await openAndConnect();

    await waitFor(() => expect(result.current.connected).toBe(true));
    expect(ws.sent.some((f) => f.includes('destination:/topic/counsel/42'))).toBe(true);
    expect(ws.sent.some((f) => f.includes('destination:/user/queue/counsel/assigned'))).toBe(true);
    expect(ws.sent.some((f) => f.includes('destination:/user/queue/counsel/ended'))).toBe(true);
  });

  it('/topic/counsel/{ticketId} 메시지 수신 시 onMessage가 호출된다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    renderHook(() => useCounselSocket(7, { onMessage }));
    const ws = await openAndConnect();

    const subId = findSubscriptionId(ws, '/topic/counsel/7');
    const payload: ChatMessageResponse = {
      id: 1,
      sessionId: 7,
      sender: 'COUNSELOR',
      content: '안녕하세요',
      attachmentUrl: null,
      createdAt: '2026-07-27T00:00:00',
    };

    act(() => {
      ws.simulateFrame(messageFrame('/topic/counsel/7', subId, payload));
    });

    expect(onMessage).toHaveBeenCalledWith(payload);
  });

  it('onAssigned/onEnded 큐 메시지도 각각 라우팅된다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();
    const onAssigned = vi.fn();
    const onEnded = vi.fn();

    renderHook(() => useCounselSocket(7, { onMessage, onAssigned, onEnded }));
    const ws = await openAndConnect();

    const assignedTicket: CounselTicketSummaryResponse = {
      id: 7,
      ticketNumber: 'CS-20260727-001',
      category: '점검 결과서 관련',
      title: '문의',
      userId: 1,
      counselorId: 5,
      status: 'IN_PROGRESS',
      queuePosition: null,
      createdAt: '2026-07-27T00:00:00',
    };
    const assignedSubId = findSubscriptionId(ws, '/user/queue/counsel/assigned');
    act(() => {
      ws.simulateFrame(messageFrame('/user/queue/counsel/assigned', assignedSubId, assignedTicket));
    });
    expect(onAssigned).toHaveBeenCalledWith(assignedTicket);

    const endedSubId = findSubscriptionId(ws, '/user/queue/counsel/ended');
    act(() => {
      ws.simulateFrame(messageFrame('/user/queue/counsel/ended', endedSubId, assignedTicket));
    });
    expect(onEnded).toHaveBeenCalledWith(assignedTicket);
  });

  it('sendMessage 호출 시 /app/counsel/{ticketId}/send로 발행한다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    const { result } = renderHook(() => useCounselSocket(9, { onMessage }));
    const ws = await openAndConnect();
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      result.current.sendMessage('안녕하세요', 'key-1');
    });

    const sendFrame = ws.sent.find((f) => f.startsWith('SEND') && f.includes('destination:/app/counsel/9/send'));
    expect(sendFrame).toBeTruthy();
    expect(sendFrame).toContain('안녕하세요');
    expect(sendFrame).toContain('key-1');
  });

  it('언마운트 시 소켓 연결을 정리한다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    const { unmount } = renderHook(() => useCounselSocket(11, { onMessage }));
    const ws = await openAndConnect();

    unmount();

    await waitFor(() => expect(ws.closed).toBe(true));
  });

  it('예기치 않은 연결 종료 후 재연결되면 구독을 다시 발행한다', async () => {
    vi.useFakeTimers();
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    const { result } = renderHook(() => useCounselSocket(30, { onMessage }));

    await vi.advanceTimersByTimeAsync(0);
    const ws0 = MockWebSocket.instances[0];
    act(() => {
      ws0.simulateOpen();
    });
    act(() => {
      ws0.simulateFrame(connectedFrame());
    });
    expect(result.current.connected).toBe(true);

    act(() => {
      ws0.close();
    });
    expect(result.current.connected).toBe(false);

    // reconnectDelay 5000ms — LINEAR/EXPONENTIAL 첫 시도는 base delay와 동일
    await vi.advanceTimersByTimeAsync(5000);

    expect(MockWebSocket.instances).toHaveLength(2);
    const ws1 = MockWebSocket.instances[1];
    act(() => {
      ws1.simulateOpen();
    });
    act(() => {
      ws1.simulateFrame(connectedFrame());
    });

    expect(result.current.connected).toBe(true);
    expect(ws1.sent.some((f) => f.includes('destination:/topic/counsel/30'))).toBe(true);
  });

  it('ERROR 프레임 수신 시 재연결을 멈추고 error를 노출한다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    const { result } = renderHook(() => useCounselSocket(40, { onMessage }));
    const ws = await openAndConnect();

    act(() => {
      ws.simulateFrame(errorFrame('상담방 구독 권한 없음'));
    });

    expect(result.current.error).toBe('상담방 구독 권한 없음');
    expect(result.current.connected).toBe(false);
    expect(ws.closed).toBe(true);
    // deactivate가 재연결 타이머를 즉시 정리하므로 새 소켓이 생기지 않는다
    expect(MockWebSocket.instances).toHaveLength(1);
  });

  it('연결이 끊긴 상태에서 sendMessage 호출 시 false를 반환하고 발행하지 않는다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    const { result } = renderHook(() => useCounselSocket(13, { onMessage }));
    const ws = await openAndConnect();
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      ws.close();
    });
    await waitFor(() => expect(result.current.connected).toBe(false));

    let sendResult: boolean | undefined;
    act(() => {
      sendResult = result.current.sendMessage('테스트');
    });

    expect(sendResult).toBe(false);
    expect(ws.sent.some((f) => f.startsWith('SEND'))).toBe(false);
  });

  it('ticketId 변경 시 이전 소켓을 정리하고 새 ticketId로 재연결한다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onMessage = vi.fn();

    const { result, rerender } = renderHook(
      ({ ticketId }) => useCounselSocket(ticketId, { onMessage }),
      { initialProps: { ticketId: 21 } },
    );
    const ws0 = await openAndConnect(0);
    await waitFor(() => expect(result.current.connected).toBe(true));

    rerender({ ticketId: 22 });

    await waitFor(() => expect(ws0.closed).toBe(true));
    const ws1 = await openAndConnect(1);
    await waitFor(() => expect(result.current.connected).toBe(true));
    expect(ws1.sent.some((f) => f.includes('destination:/topic/counsel/22'))).toBe(true);
  });
});
