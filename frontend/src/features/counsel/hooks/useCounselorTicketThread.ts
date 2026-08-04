import { useCallback, useEffect, useState } from 'react';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { useCounselSocket } from './useCounselSocket';
import type {
  ChatMessageResponse,
  ChatMessageSender,
  CounselTicketStatus,
  CounselTicketSummaryResponse,
} from '../types';

/** 종료된 티켓 상태 — 상담원이 더 이상 메시지를 보낼 수 없는 상태 집합 */
function isEndedStatus(status: CounselTicketStatus | undefined): boolean {
  return status === 'RESOLVED' || status === 'OFFLINE_LEFT';
}

// 상담원 콘솔 마스터-디테일(#1001, HAJA-495) — 선택된 티켓의 대화(메시지+소켓+종료)를 캡슐화한다.
// 기존 CounselorChatPage 내부 로직을 그대로 훅으로 분리한 것 — 대화 조회(GET .../messages)는
// 담당 상담원(또는 PLATFORM_ADMIN)만 가능하므로(백엔드 loadParticipantTicket 검증), WAITING(미배정)
// 티켓에 대해 호출하면 403이 난다 — 그 경우는 호출부(CounselorChatWindow)가 배정 CTA로 분기해
// 아예 이 훅을 구독하지 않는다(ticketId=null로 훅을 비활성화).
export function useCounselorTicketThread(ticketId: number | null, onEnded: () => void) {
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(true);
  const [messagesError, setMessagesError] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [resolveError, setResolveError] = useState<string | null>(null);
  // #1506 — 고객이 원격으로 종료한 경우(onEnded 소켓 이벤트)는 상담원 본인이 "상담 종료" 버튼을
  // 누른 경우(resolve() 성공)와 달리 화면을 즉시 이동시키지 않는다. 대신 이 상태를 채워
  // 소비자(CounselorChatWindow)가 그 자리에서 종료 안내 UI로 전환하게 한다.
  const [remoteEndedTicket, setRemoteEndedTicket] = useState<CounselTicketSummaryResponse | null>(null);
  // #1590 — 소비자(CounselorChatWindow)가 넘겨주는 ticket은 목록/navigate state 스냅샷이라 원격
  // 종료 후에도 갱신되지 않는다(종료된 티켓은 WAITING/IN_PROGRESS 조회에서 빠져 큐 reload로도
  // 최신화되지 않는다). 그래서 티켓을 열 때마다 서버에서 최신 상태를 1회 조회해 종료 판정의
  // 소스로 쓴다(#1506이 useChatBot에 넣은 REST 백필과 동일 패턴).
  const [latestTicket, setLatestTicket] = useState<CounselTicketSummaryResponse | null>(null);
  // 위 조회가 끝나기 전까지는 종료 여부가 "미확정"이다(#1590 리뷰 P3) — 이 창에서 isEnded=false로
  // 단정하면 종료된 티켓인데도 "상담 종료" 버튼·입력창이 잠깐 열렸다가 닫힌다(그 사이 전송하면
  // 서버가 거부). 소비자가 확정 전까지 조작을 잠글 수 있도록 함께 노출한다.
  const [endedPending, setEndedPending] = useState(false);

  useEffect(() => {
    if (ticketId === null) {
      setMessages([]);
      setMessagesLoading(false);
      setMessagesError(null);
      return;
    }
    let cancelled = false;
    setMessagesLoading(true);
    setMessagesError(null);
    counselApi
      .getMessages(ticketId)
      .then((res) => {
        if (!cancelled) setMessages(res.data);
      })
      .catch((err: unknown) => {
        if (!cancelled) setMessagesError(getApiErrorMessage(err, '대화를 불러오지 못했습니다.'));
      })
      .finally(() => {
        if (!cancelled) setMessagesLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [ticketId]);

  // 고객 "입력 중" 표시(#1001 후속) — 신호 수신마다 타이머를 리셋해 일정 시간 추가 신호가 없으면
  // 자동으로 숨긴다(ConversationPanel쪽 useCounselHistory와 동일한 idle-timeout 방식).
  const [customerTyping, setCustomerTyping] = useState(false);
  const handleTyping = useCallback((sender: ChatMessageSender) => {
    if (sender !== 'USER') return;
    setCustomerTyping(true);
  }, []);

  useEffect(() => {
    if (!customerTyping) return;
    const timer = window.setTimeout(() => setCustomerTyping(false), 3000);
    return () => window.clearTimeout(timer);
  }, [customerTyping]);

  useEffect(() => {
    setCustomerTyping(false);
    setRemoteEndedTicket(null);
  }, [ticketId]);

  // #1590 — 티켓 전환 시마다 서버 최신 상태를 백필한다. 이 조회가 없으면 원격 종료 후 다른 티켓을
  // 거쳐 되돌아왔을 때 remoteEndedTicket이 리셋되고 stale한 ticket prop만 남아 종료 표시가 사라지고
  // 입력창이 다시 열린다(전송하면 서버가 거부).
  useEffect(() => {
    setLatestTicket(null);
    if (ticketId === null) {
      setEndedPending(false);
      return;
    }

    let cancelled = false;
    setEndedPending(true);
    counselApi
      .getTicket(ticketId)
      .then((res) => {
        if (!cancelled) setLatestTicket(res.data);
      })
      .catch(() => {
        // 조회 실패는 조용히 무시 — 종료 판정은 소켓(onEnded)과 호출부의 ticket 스냅샷으로 폴백된다.
      })
      .finally(() => {
        if (!cancelled) setEndedPending(false);
      });
    return () => {
      cancelled = true;
    };
  }, [ticketId]);

  const { connected, sendMessage, sendTyping } = useCounselSocket(ticketId, {
    onMessage: (message) => setMessages((prev) => [...prev, message]),
    // 다른 경로(예: 고객 종료, 이용자 오프라인 이탈, PLATFORM_ADMIN 강제 종료)로 티켓이 끝나도 화면이
    // 이를 반영해야 하지만, 상위 onEnded(부모의 navigate-away)를 곧바로 부르지는 않는다 — 원격 종료는
    // 화면을 그 자리에서 "종료" UI로 전환할 뿐 이동시키지 않는다(#1506). 직접 종료 버튼을 누른 경우는
    // resolve()가 성공 시 onEnded()를 별도로 호출한다.
    onEnded: setRemoteEndedTicket,
    onTyping: handleTyping,
  });

  async function resolve() {
    if (ticketId === null) return;
    setResolving(true);
    setResolveError(null);
    try {
      await counselApi.resolve(ticketId);
      onEnded();
    } catch (err) {
      setResolveError(getApiErrorMessage(err, '상담 종료에 실패했습니다.'));
    } finally {
      setResolving(false);
    }
  }

  // 종료 판정 소스(#1590): 소켓으로 받은 원격 종료 > 서버 최신 상태 순. 둘 다 없으면 호출부가
  // 자신의 ticket 스냅샷으로 보조 판정한다(이미 종료된 티켓을 목록에서 눌러 들어온 경우).
  const endedTicket =
    remoteEndedTicket ?? (isEndedStatus(latestTicket?.status) ? latestTicket : null);

  return {
    messages,
    messagesLoading,
    messagesError,
    connected,
    sendMessage,
    sendTyping,
    customerTyping,
    resolving,
    resolveError,
    resolve,
    endedTicket,
    isEnded: endedTicket !== null,
    // 확정 전 창(#1590 리뷰 P3) — 소켓으로 이미 종료를 받았으면 확정된 것이므로 pending이 아니다.
    endedPending: endedPending && remoteEndedTicket === null,
  };
}
