import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { counselApi } from '../api/counselApi';
import type { ChatMessageResponse } from '../types';

// 플랫폼 관리자 상담 관리(#1168) — 선택된 티켓의 GET /api/counsel/tickets/{id}/messages
// react-query 래핑. 프론트는 시그니처 변경이 없고(계획 §2.2), PLATFORM_ADMIN 우회 여부는 서버가
// 세션/토큰으로 판별한다 — 여기선 ticketId가 있을 때만 조회하도록 enabled로 가드한다.
export function useAdminCounselTranscript(ticketId: number | null) {
  return useQuery<ChatMessageResponse[], ApiError>({
    queryKey: ['counsel', 'admin-transcript', ticketId],
    queryFn: () => counselApi.getMessages(ticketId as number).then((res) => res.data),
    enabled: ticketId !== null,
  });
}
