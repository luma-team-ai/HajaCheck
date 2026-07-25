import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type {
  ChatMessageResponse,
  CounselTicketListFilters,
  CounselTicketSummaryResponse,
} from '../types';

export const counselApi = {
  // GET /api/counsel/tickets/mine — 마이페이지 > 내 상담 이력 목록(상태 필터 + 페이지네이션)
  getTickets: (filters: CounselTicketListFilters = {}) =>
    api.get<PageResponse<CounselTicketSummaryResponse>>('/counsel/tickets/mine', {
      params: filters,
    }),
  // GET /api/counsel/tickets/{id}/messages — 티켓 대화 전체 메시지
  getMessages: (ticketId: number) =>
    api.get<ChatMessageResponse[]>(`/counsel/tickets/${ticketId}/messages`),
  // GET /api/counsel/tickets/{id}/export — 대화 내보내기(.txt). 파일명은 Content-Disposition
  // 헤더로 오므로 blob 응답을 그대로 반환해 호출부(다운로드 유틸)가 헤더+본문을 함께 처리한다.
  // shared axios 인터셉터가 blob 응답은 envelope unwrap을 건너뛰도록 처리돼 있다(axios.ts 참고).
  exportConversation: (ticketId: number) =>
    api.get<Blob>(`/counsel/tickets/${ticketId}/export`, { responseType: 'blob' }),
};
