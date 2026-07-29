import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import type { ChatMessageResponse } from '../types';

// 플랫폼 관리자 상담 관리(#1168) — 우측 정보 패널 "이력" 탭에서 고른 과거 티켓의 대화를
// 중앙 대화 패널에 표시하기 위한 조회 훅. GET /api/counsel/tickets/{ticketId}/customer-history/
// {historyId}/messages 를 감싼다(useAdminCounselTranscript와 동일 패턴, currentTicketId 기준).
export function useAdminCounselHistoryTranscript(currentTicketId: number | null, historyTicketId: number | null) {
  return useQuery<ChatMessageResponse[], ApiError>({
    queryKey: ['counsel', 'admin-history-transcript', currentTicketId, historyTicketId],
    queryFn: () =>
      counselApi
        .getCustomerHistoryMessages(currentTicketId as number, historyTicketId as number)
        .then((res) => res.data),
    enabled: currentTicketId !== null && historyTicketId !== null,
  });
}
