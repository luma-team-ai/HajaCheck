import { useCallback, useEffect, useState } from 'react';
import { getApiErrorMessage } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import { DEFAULT_PAGE_SIZE } from '../constants';
import type {
  ChatMessageResponse,
  CounselTicketStatusFilter,
  CounselTicketSummaryResponse,
} from '../types';

// 마이페이지 > 내 상담 이력(#20, HAJA-33) — 목록(상태 필터) + 선택한 티켓의 대화를 함께 관리한다.
// 목록이 바뀌면(필터 전환) 첫 번째 티켓을 자동 선택한다(Figma: 진입 시 최신 티켓이 우측에 열려 있음).
export function useCounselHistory() {
  const [status, setStatus] = useState<CounselTicketStatusFilter>('ALL');
  const [tickets, setTickets] = useState<CounselTicketSummaryResponse[]>([]);
  const [ticketsLoading, setTicketsLoading] = useState(false);
  const [ticketsError, setTicketsError] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [messagesError, setMessagesError] = useState<string | null>(null);

  const loadTickets = useCallback(async (filter: CounselTicketStatusFilter) => {
    setTicketsLoading(true);
    setTicketsError(null);
    try {
      const res = await counselApi.getTickets({ status: filter, page: 0, size: DEFAULT_PAGE_SIZE });
      setTickets(res.data.content);
      // 필터 전환 후 이전 선택이 새 목록에 없으면(예: '진행중'에서 '종료'로 전환) 첫 항목으로 갱신.
      setSelectedId((prev) => {
        if (prev !== null && res.data.content.some((t) => t.id === prev)) return prev;
        return res.data.content[0]?.id ?? null;
      });
    } catch (err) {
      setTicketsError(getApiErrorMessage(err, '상담 이력을 불러오지 못했습니다.'));
      setTickets([]);
      setSelectedId(null);
    } finally {
      setTicketsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTickets(status);
  }, [status, loadTickets]);

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
  };
}
