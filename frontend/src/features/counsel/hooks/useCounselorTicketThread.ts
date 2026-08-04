import { useCallback, useEffect, useState } from 'react';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { useCounselSocket } from './useCounselSocket';
import type { ChatMessageResponse, ChatMessageSender, CounselTicketSummaryResponse } from '../types';

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
    remoteEndedTicket,
  };
}
