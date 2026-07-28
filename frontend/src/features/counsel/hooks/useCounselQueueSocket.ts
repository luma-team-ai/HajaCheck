import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import { useEffect, useRef, useState } from 'react';

// 상담원 대기열 실시간 갱신(#1001 후속, 사용자 피드백: "신청하면 상담원이 새로고침해야 보임").
// 동일 오리진 handshake라 세션 쿠키가 자동 포함된다(useCounselSocket과 동일한 전제).
// 끝에 슬래시 필수 — nginx 경유 시 트레일링 슬래시 없는 '/ws'만 301을 돌려줘 브라우저 WebSocket
// 연결이 실패한다(useCounselSocket.ts의 buildWsUrl 주석 참고, 동일 원인).
function buildWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/`;
}

// 새 상담 신청 시 백엔드(CounselTicketService#createTicket)가 /topic/counsel-queue로 신호만
// 보낸다(티켓 내용 없음 — StompAuthChannelInterceptor가 COUNSELOR/PLATFORM_ADMIN만 구독 허용).
// 신호를 받으면 onQueueUpdated로 REST 재조회를 트리거해 목록을 갱신한다(페이로드 자체를 신뢰해
// 화면에 반영하지 않고, 항상 REST를 단일 소스로 삼는다 — useCounselSocket의 onAssigned/onEnded와
// 달리 이 신호는 "누가" 신청했는지도 모르는 최소 정보라 그대로 반영할 데이터가 없다).
export function useCounselQueueSocket(enabled: boolean, onQueueUpdated: () => void): { connected: boolean } {
  const [connected, setConnected] = useState(false);
  const onQueueUpdatedRef = useRef(onQueueUpdated);
  onQueueUpdatedRef.current = onQueueUpdated;

  useEffect(() => {
    if (!enabled) {
      setConnected(false);
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
      client.subscribe('/topic/counsel-queue', () => {
        onQueueUpdatedRef.current();
      });
    };

    client.onWebSocketClose = () => {
      setConnected(false);
    };

    client.activate();

    return () => {
      setConnected(false);
      void client.deactivate({ force: true });
    };
  }, [enabled]);

  return { connected };
}
