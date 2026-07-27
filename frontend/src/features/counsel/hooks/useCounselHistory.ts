import { useCallback, useEffect, useMemo, useState } from 'react';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { DEFAULT_PAGE_SIZE, isTicketEnded } from '../constants';
import { useCounselSocket } from './useCounselSocket';
import type {
  ChatMessageResponse,
  CounselTicketStatusFilter,
  CounselTicketSummaryResponse,
} from '../types';

// 마이페이지 > 내 상담 이력(#20, HAJA-33) — 목록(상태 필터) + 선택한 티켓의 대화를 함께 관리한다.
// 목록이 바뀌면(필터 전환) 첫 번째 티켓을 자동 선택한다(Figma: 진입 시 최신 티켓이 우측에 열려 있음).
export function useCounselHistory() {
  const [status, setStatus] = useState<CounselTicketStatusFilter>('ALL');
  const [allTickets, setAllTickets] = useState<CounselTicketSummaryResponse[]>([]);
  const [ticketsLoading, setTicketsLoading] = useState(false);
  const [ticketsError, setTicketsError] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [messagesError, setMessagesError] = useState<string | null>(null);

  const loadTickets = useCallback(async () => {
    setTicketsLoading(true);
    setTicketsError(null);
    try {
      const res = await counselApi.getTickets({ status: 'ALL', page: 0, size: DEFAULT_PAGE_SIZE });
      setAllTickets(res.data.content);
    } catch (err) {
      setTicketsError(getApiErrorMessage(err, '상담 이력을 불러오지 못했습니다.'));
      setAllTickets([]);
    } finally {
      setTicketsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTickets();
  }, [loadTickets]);

  // 카드 뱃지(STATUS_BADGE)는 WAITING+IN_PROGRESS를 "진행중"으로, RESOLVED+OFFLINE_LEFT를
  // "종료"로 묶어 보여주는데, 백엔드 status 쿼리는 단일 enum만 받아 이 그룹을 표현하지 못한다
  // (탭에서 status=IN_PROGRESS만 보내면 WAITING 티켓이 빠져 "전체엔 있는데 탭엔 없음" 불일치가 남).
  // 항상 전체를 받아 뱃지와 동일한 기준으로 프론트에서 걸러 일치시킨다.
  const tickets = useMemo(() => {
    if (status === 'ALL') return allTickets;
    if (status === 'RESOLVED') return allTickets.filter((t) => isTicketEnded(t.status));
    return allTickets.filter((t) => !isTicketEnded(t.status));
  }, [status, allTickets]);

  useEffect(() => {
    // 필터 전환 후 이전 선택이 새 목록에 없으면(예: '진행중'에서 '종료'로 전환) 첫 항목으로 갱신.
    setSelectedId((prev) => {
      if (prev !== null && tickets.some((t) => t.id === prev)) return prev;
      return tickets[0]?.id ?? null;
    });
  }, [tickets]);

  useEffect(() => {
    if (selectedId === null) {
      setMessages([]);
      return;
    }
    let cancelled = false;
    setMessagesLoading(true);
    setMessagesError(null);
    counselApi
      .getMessages(selectedId)
      .then((res) => {
        if (!cancelled) setMessages(res.data);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setMessagesError(getApiErrorMessage(err, '대화를 불러오지 못했습니다.'));
          setMessages([]);
        }
      })
      .finally(() => {
        if (!cancelled) setMessagesLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  const selectedTicket = tickets.find((t) => t.id === selectedId) ?? null;

  // 실시간 소켓(#1000, HAJA-494) — WAITING(배정 대기, onAssigned로 배정 즉시 전환 감지)과
  // IN_PROGRESS(실시간 채팅, onMessage+onEnded) 상태에서만 연결한다. RESOLVED/OFFLINE_LEFT는
  // 이미 종료된 읽기 전용 대화라 소켓이 필요 없다.
  const isSocketActive =
    selectedTicket !== null &&
    (selectedTicket.status === 'WAITING' || selectedTicket.status === 'IN_PROGRESS');
  const socketTicketId = isSocketActive ? selectedTicket.id : null;

  const handleSocketMessage = useCallback((message: ChatMessageResponse) => {
    // REST로 이미 로드된 메시지와 겹치지 않도록 id 기준 dedupe.
    setMessages((prev) => (prev.some((m) => m.id === message.id) ? prev : [...prev, message]));
  }, []);

  // 배정/종료 이벤트는 개별 티켓 상태를 서버가 보낸 최신 스냅샷으로 그대로 갱신한다
  // (재조회 대신 페이로드를 직접 반영해 왕복 없이 즉시 화면을 전환한다).
  const handleTicketUpdate = useCallback((ticket: CounselTicketSummaryResponse) => {
    setAllTickets((prev) => prev.map((t) => (t.id === ticket.id ? ticket : t)));
  }, []);

  const { connected, sendMessage } = useCounselSocket(socketTicketId, {
    onMessage: handleSocketMessage,
    onAssigned: handleTicketUpdate,
    onEnded: handleTicketUpdate,
  });

  return {
    status,
    setStatus,
    tickets,
    ticketsLoading,
    ticketsError,
    selectedId,
    selectTicket: setSelectedId,
    selectedTicket,
    messages,
    messagesLoading,
    messagesError,
    socketConnected: connected,
    sendMessage,
  };
}
