import { useEffect, useState } from 'react';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { useCounselSocket } from './useCounselSocket';
import type { ChatMessageResponse } from '../types';

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

  const { connected, sendMessage } = useCounselSocket(ticketId, {
    onMessage: (message) => setMessages((prev) => [...prev, message]),
    // 다른 경로(예: 이용자 오프라인 이탈, PLATFORM_ADMIN 강제 종료)로 티켓이 끝나도 화면이 이를
    // 반영하도록 onEnded도 구독한다 — 직접 종료 버튼을 누른 경우는 resolve()가 이미 처리.
    onEnded,
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
    resolving,
    resolveError,
    resolve,
  };
}
