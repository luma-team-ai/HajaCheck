import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import type { IFrame } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatMessageResponse, ChatMessageSender, CounselTicketSummaryResponse } from '../types';

// 상담원-고객 실시간 연결 선행 작업(#999, HAJA-493) — STOMP 소켓 연결/구독/발행을 캡슐화한 훅.
// 백엔드는 SockJS를 지원하지 않는 raw WebSocket 엔드포인트(`/ws`)만 제공하므로
// webSocketFactory로 순정 WebSocket을 직접 생성한다(SockJS 클라이언트 사용 금지).
// 동일 오리진 핸드셰이크라 세션 쿠키가 자동 포함돼 별도 인증 헤더가 필요 없다.
export interface UseCounselSocketHandlers {
  onMessage: (message: ChatMessageResponse) => void;
  onAssigned?: (ticket: CounselTicketSummaryResponse) => void;
  onEnded?: (ticket: CounselTicketSummaryResponse) => void;
  // 상대방 "입력 중" 신호(#1000/#1001 후속) — 영속화되지 않는 휘발성 이벤트라 메시지 로그에 남기지 않는다.
  onTyping?: (sender: ChatMessageSender) => void;
}

export interface UseCounselSocketResult {
  connected: boolean;
  // CONNECT 재검증 실패(세션 만료) 또는 SUBSCRIBE 인가 거부(비당사자 ticketId) 시 채워진다.
  // 이 경우 재연결을 멈추므로, 소비자가 로그인 유도/토스트 등을 띄우는 신호로 쓴다.
  error: string | null;
  sendMessage: (content: string, attachmentKey?: string) => boolean;
  // "입력 중" 신호 발행 — 페이로드 없음(발신자는 서버가 Principal로 식별). sendMessage와 동일하게
  // 연결 안 됐으면 조용히 no-op(false 반환).
  sendTyping: () => boolean;
}

// 프로덕션 빌드에서 WS URL 하드코딩 금지 — axios(shared/api/axios.ts)와 동일하게 상대 경로 기준으로
// 현재 오리진의 프로토콜(ws/wss)만 붙인다. dev에서는 vite.config.ts의 '/ws' 프록시(ws:true)가
// 프론트 포트→스프링 포트로 그대로 전달한다.
// 끝에 슬래시 필수 — nginx(dev.conf/default.conf) 경유 시 트레일링 슬래시 없는 정확히 '/ws'만
// 301 Moved Permanently를 돌려주고(nginx 자체가 응답 — Set-Cookie 등 Spring 헤더가 전혀 없어
// 백엔드까지 도달하지 않은 게 curl로 확인됨), 브라우저 WebSocket은 리다이렉트를 따라가지 못해
// 그대로 연결 실패로 끝난다. '/ws/'·'/ws/아무거나'는 전부 정상 통과 확인.
function buildWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/`;
}

// STOMP 프레임 바디는 서버가 보낸 JSON을 그대로 신뢰할 수 없다(레이스로 인한 프레임 순서 꼬임 등) —
// throw하면 stompjs 내부 catch가 콘솔 로그만 남기고 삼켜, 메시지가 흔적 없이 사라진다.
function parseFrameBody<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

export function useCounselSocket(
  ticketId: number | null,
  handlers: UseCounselSocketHandlers,
): UseCounselSocketResult {
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const clientRef = useRef<Client | null>(null);
  // 콜백 최신값을 ref로 들고 있어야 STOMP 구독 콜백(연결 시점에 클로저로 고정)이
  // 매 렌더마다 재구독 없이도 최신 핸들러를 참조할 수 있다.
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (ticketId === null) {
      setConnected(false);
      setError(null);
      return;
    }

    const client = new Client({
      webSocketFactory: () => new WebSocket(buildWsUrl()),
      reconnectDelay: 5000,
      maxReconnectDelay: 60000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    });

    client.onConnect = () => {
      setConnected(true);
      setError(null);

      client.subscribe(`/topic/counsel/${ticketId}`, (frame) => {
        const message = parseFrameBody<ChatMessageResponse>(frame.body);
        if (message === null) {
          setError('상담 메시지 파싱 실패');
          return;
        }
        handlersRef.current.onMessage(message);
      });

      client.subscribe('/user/queue/counsel/assigned', (frame) => {
        const ticket = parseFrameBody<CounselTicketSummaryResponse>(frame.body);
        if (ticket === null) {
          setError('상담 배정 알림 파싱 실패');
          return;
        }
        handlersRef.current.onAssigned?.(ticket);
      });

      client.subscribe('/user/queue/counsel/ended', (frame) => {
        const ticket = parseFrameBody<CounselTicketSummaryResponse>(frame.body);
        if (ticket === null) {
          setError('상담 종료 알림 파싱 실패');
          return;
        }
        handlersRef.current.onEnded?.(ticket);
      });

      // "입력 중" 신호 — 파싱 실패해도 부가 UI일 뿐이라 error state를 채우지 않고 조용히 무시한다
      // (메시지/배정/종료와 달리 실패해도 상담 진행 자체엔 영향 없음).
      client.subscribe(`/topic/counsel/${ticketId}/typing`, (frame) => {
        const typing = parseFrameBody<{ sender: ChatMessageSender }>(frame.body);
        if (typing === null) return;
        handlersRef.current.onTyping?.(typing.sender);
      });
    };

    client.onWebSocketClose = () => {
      setConnected(false);
    };

    // 서버는 CONNECT 재검증(세션 만료) 또는 SUBSCRIBE 인가(비당사자 ticketId)를 거부할 때만
    // ERROR 프레임을 보낸다(StompAuthChannelInterceptor) — 즉 이 콜백이 불리는 경우는 전부
    // 인증/인가 실패다. 아무 조치 없이 두면 소켓 close → active 상태라 재시도 스케줄 →
    // 같은 사유로 다시 거부되는 영구 루프가 된다. deactivate로 재시도를 끊고 에러를 노출해
    // 소비자가 로그인 유도/에러 표시를 하게 한다.
    client.onStompError = (frame: IFrame) => {
      setError(frame.headers.message ?? '상담 연결이 거부되었습니다');
      void client.deactivate({ force: true });
    };

    client.onWebSocketError = () => {
      setError('상담 서버에 연결할 수 없습니다');
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
      // 옵셔널 체이닝은 client가 null인 경우만 막는다 — 재연결 대기 중(client는 있지만
      // STOMP 연결은 끊긴 상태)엔 stompjs의 publish()가 TypeError를 던져 이벤트 핸들러
      // 밖으로 uncaught 예외가 튀고 메시지가 흔적 없이 사라진다. connected로 직접 가드한다.
      if (ticketId === null || !clientRef.current?.connected) return false;
      clientRef.current.publish({
        destination: `/app/counsel/${ticketId}/send`,
        body: JSON.stringify({ content, attachmentKey }),
      });
      return true;
    },
    [ticketId],
  );

  const sendTyping = useCallback(() => {
    if (ticketId === null || !clientRef.current?.connected) return false;
    clientRef.current.publish({ destination: `/app/counsel/${ticketId}/typing`, body: '' });
    return true;
  }, [ticketId]);

  return { connected, error, sendMessage, sendTyping };
}
