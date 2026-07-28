import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { DEFAULT_PAGE_SIZE, isTicketEnded } from '../constants';
import { useCounselSocket } from './useCounselSocket';
import type {
  ChatMessageResponse,
  ChatMessageSender,
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

  // 알림센터 "대화 열기"(COUNSEL_REPLIED) 딥링크 — 진입 시 ?ticketId=로 특정 티켓을 바로 선택한다
  // (이전엔 이 값을 아예 읽지 않아 버튼을 눌러도 상담창으로 넘어가지 않는 버그였다). 목록 로드 후
  // 그 티켓이 실제로 있으면 1회만 선택에 반영하고 소진한다 — 계속 강제 선택하면 이후 사용자가 다른
  // 티켓을 고른 뒤에도 소켓 갱신 등으로 tickets가 바뀔 때마다 도로 튕겨나가는 회귀가 생긴다.
  const [searchParams, setSearchParams] = useSearchParams();
  const [pendingDeepLinkTicketId, setPendingDeepLinkTicketId] = useState<number | null>(() => {
    const raw = searchParams.get('ticketId');
    const parsed = raw ? Number(raw) : NaN;
    return Number.isFinite(parsed) ? parsed : null;
  });

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
    const deepLinkTicket =
      pendingDeepLinkTicketId !== null ? tickets.find((t) => t.id === pendingDeepLinkTicketId) : undefined;
    // 필터 전환 후 이전 선택이 새 목록에 없으면(예: '진행중'에서 '종료'로 전환) 첫 항목으로 갱신.
    setSelectedId((prev) => {
      if (deepLinkTicket) return deepLinkTicket.id;
      if (prev !== null && tickets.some((t) => t.id === prev)) return prev;
      return tickets[0]?.id ?? null;
    });
    if (deepLinkTicket) {
      setPendingDeepLinkTicketId(null);
      setSearchParams((prev) => {
        const next = new URLSearchParams(prev);
        next.delete('ticketId');
        return next;
      }, { replace: true });
    }
  }, [tickets, pendingDeepLinkTicketId, setSearchParams]);

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

  // 상대방(상담원) "입력 중" 표시(#1000 후속) — 신호를 받을 때마다 타이머를 리셋해 일정 시간
  // 추가 신호가 없으면 자동으로 숨긴다(서버가 "입력 종료" 이벤트를 별도로 주지 않음, 클라 측
  // idle-timeout 방식으로 처리 — AiAssistantPage 로딩 버블과 달리 명시적 종료 신호가 없어서 필요).
  const [counselorTyping, setCounselorTyping] = useState(false);
  const handleTyping = useCallback((sender: ChatMessageSender) => {
    if (sender !== 'COUNSELOR') return;
    setCounselorTyping(true);
  }, []);

  useEffect(() => {
    if (!counselorTyping) return;
    const timer = window.setTimeout(() => setCounselorTyping(false), 3000);
    return () => window.clearTimeout(timer);
  }, [counselorTyping]);

  useEffect(() => {
    setCounselorTyping(false);
  }, [selectedId]);

  const { connected, sendMessage, sendTyping } = useCounselSocket(socketTicketId, {
    onMessage: handleSocketMessage,
    onAssigned: handleTicketUpdate,
    onEnded: handleTicketUpdate,
    onTyping: handleTyping,
  });

  // 상담 종료(#1000 후속: 고객도 종료 가능 — 백엔드 CounselTicketService#resolve 권한 완화와 짝).
  const [ending, setEnding] = useState(false);
  const [endError, setEndError] = useState<string | null>(null);
  const endCounsel = useCallback(async () => {
    if (selectedId === null) return;
    setEnding(true);
    setEndError(null);
    try {
      const res = await counselApi.resolve(selectedId);
      handleTicketUpdate(res.data);
    } catch (err) {
      setEndError(getApiErrorMessage(err, '상담 종료에 실패했습니다.'));
    } finally {
      setEnding(false);
    }
  }, [selectedId, handleTicketUpdate]);

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
    sendTyping,
    counselorTyping,
    endCounsel,
    ending,
    endError,
  };
}
