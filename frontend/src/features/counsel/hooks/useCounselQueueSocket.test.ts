// @vitest-environment jsdom
// useCounselQueueSocket 단위 테스트(#1001 후속) — /topic/counsel-queue 신호 수신 시
// onQueueUpdated가 호출되는지, enabled=false면 연결하지 않는지 검증한다.
// MockWebSocket 패턴은 useCounselSocket.test.ts와 동일(별도 서버 없이 STOMP 프레임 시뮬레이션).
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useCounselQueueSocket } from './useCounselQueueSocket';

const OPEN = 1;
const CLOSED = 3;

class MockWebSocket {
  static instances: MockWebSocket[] = [];

  url: string;
  readyState = 0;
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

function messageFrame(destination: string, subscriptionId: string, body: string) {
  return `MESSAGE\ndestination:${destination}\nsubscription:${subscriptionId}\nmessage-id:1\ncontent-type:text/plain\n\n${body}\0`;
}

function findSubscriptionId(ws: MockWebSocket, destination: string): string {
  for (const frame of ws.sent) {
    if (!frame.startsWith('SUBSCRIBE')) continue;
    if (!frame.includes(`destination:${destination}`)) continue;
    const match = /id:([^\n]+)/.exec(frame);
    if (match) return match[1];
  }
  throw new Error(`subscribe frame not found for ${destination}`);
}

async function openAndConnect() {
  await waitFor(() => expect(MockWebSocket.instances.length).toBeGreaterThan(0));
  const ws = MockWebSocket.instances[0];
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

describe('useCounselQueueSocket', () => {
  const originalWebSocket = globalThis.WebSocket;

  afterEach(() => {
    MockWebSocket.instances = [];
    globalThis.WebSocket = originalWebSocket;
    vi.restoreAllMocks();
  });

  it('enabled=true면 연결하고 /topic/counsel-queue 신호 수신 시 onQueueUpdated를 호출한다', async () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onQueueUpdated = vi.fn();

    const { result } = renderHook(() => useCounselQueueSocket(true, onQueueUpdated));

    const ws = await openAndConnect();
    await waitFor(() => expect(result.current.connected).toBe(true));

    const subId = findSubscriptionId(ws, '/topic/counsel-queue');
    act(() => {
      ws.simulateFrame(messageFrame('/topic/counsel-queue', subId, 'WAITING_TICKET_CREATED'));
    });

    expect(onQueueUpdated).toHaveBeenCalledTimes(1);
  });

  it('enabled=false면 연결하지 않는다', () => {
    globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
    const onQueueUpdated = vi.fn();

    renderHook(() => useCounselQueueSocket(false, onQueueUpdated));

    expect(MockWebSocket.instances.length).toBe(0);
  });
});
