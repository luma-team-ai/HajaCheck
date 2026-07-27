import { Client } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatMessageResponse, CounselTicketSummaryResponse } from '../types';

// 상담원-고객 실시간 연결 선행 작업(#999, HAJA-493) — STOMP 소켓 연결/구독/발행을 캡슐화한 훅.
// 백엔드는 SockJS를 지원하지 않는 raw WebSocket 엔드포인트(`/ws`)만 제공하므로
// webSocketFactory로 순정 WebSocket을 직접 생성한다(SockJS 클라이언트 사용 금지).
// 동일 오리진 핸드셰이크라 세션 쿠키가 자동 포함돼 별도 인증 헤더가 필요 없다.
export interface UseCounselSocketHandlers {
  onMessage: (message: ChatMessageResponse) => void;
  onAssigned?: (ticket: CounselTicketSummaryResponse) => void;
  onEnded?: (ticket: CounselTicketSummaryResponse) => void;
}

// 프로덕션 빌드에서 WS URL 하드코딩 금지 — axios(shared/api/axios.ts)와 동일하게 상대 경로 기준으로
// 현재 오리진의 프로토콜(ws/wss)만 붙인다. dev에서는 vite.config.ts의 '/ws' 프록시(ws:true)가
// 프론트 포트→스프링 포트로 그대로 전달한다.
function buildWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}

export function useCounselSocket(ticketId: number | null, handlers: UseCounselSocketHandlers) {
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  // 콜백 최신값을 ref로 들고 있어야 STOMP 구독 콜백(연결 시점에 클로저로 고정)이
  // 매 렌더마다 재구독 없이도 최신 핸들러를 참조할 수 있다.
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (ticketId === null) {
      setConnected(false);
      return;
    }

    const client = new Client({
      webSocketFactory: () => new WebSocket(buildWsUrl()),
      reconnectDelay: 5000,
    });

    client.onConnect = () => {
      setConnected(true);

      client.subscribe(`/topic/counsel/${ticketId}`, (frame) => {
        const message = JSON.parse(frame.body) as ChatMessageResponse;
        handlersRef.current.onMessage(message);
      });

      if (handlersRef.current.onAssigned) {
        client.subscribe('/user/queue/counsel/assigned', (frame) => {
          const ticket = JSON.parse(frame.body) as CounselTicketSummaryResponse;
          handlersRef.current.onAssigned?.(ticket);
        });
      }

      if (handlersRef.current.onEnded) {
        client.subscribe('/user/queue/counsel/ended', (frame) => {
          const ticket = JSON.parse(frame.body) as CounselTicketSummaryResponse;
          handlersRef.current.onEnded?.(ticket);
        });
      }
    };

    client.onWebSocketClose = () => {
      setConnected(false);
    };

    client.activate();
    clientRef.current = client;

    return () => {
      clientRef.current = null;
      setConnected(false);
      // force:true — graceful DISCONNECT는 서버 receipt를 기다리는데, 언마운트 시점엔 그 응답을
      // 기다릴 이유가 없다(오히려 뒤늦게 도착한 receipt가 이미 사라진 클로저를 건드릴 여지만 커짐).
      // 소켓을 즉시 폐기해 정리를 확정적으로 끝낸다.
      void client.deactivate({ force: true });
    };
  }, [ticketId]);

  const sendMessage = useCallback(
    (content: string, attachmentKey?: string) => {
      if (ticketId === null) return;
      clientRef.current?.publish({
        destination: `/app/counsel/${ticketId}/send`,
        body: JSON.stringify({ content, attachmentKey }),
      });
    },
    [ticketId],
  );

  return { connected, sendMessage };
}
