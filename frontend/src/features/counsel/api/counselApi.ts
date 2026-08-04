import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type {
  BotScenarioButtonResponse,
  BotScenarioNodeResponse,
  ChatMessageResponse,
  CounselQueueFilters,
  CounselTicketCreateRequest,
  CounselTicketDetailResponse,
  CounselTicketListFilters,
  CounselTicketNoteResponse,
  CounselTicketNoteUpdateRequest,
  CounselTicketSummaryResponse,
} from '../types';

export const counselApi = {
  // GET /api/counsel/tickets/mine — 마이페이지 > 내 상담 이력 목록(상태 필터 + 페이지네이션)
  getTickets: (filters: CounselTicketListFilters = {}) =>
    api.get<PageResponse<CounselTicketSummaryResponse>>('/counsel/tickets/mine', {
      params: filters,
    }),
  // GET /api/counsel/scenarios/roots — 챗봇 첫 화면 최상위 버튼 목록
  getScenarioRoots: () => api.get<BotScenarioButtonResponse[]>('/counsel/scenarios/roots'),
  // GET /api/counsel/scenarios/{id} — 노드 응답 텍스트 + 자식 버튼
  getScenarioNode: (id: number) => api.get<BotScenarioNodeResponse>(`/counsel/scenarios/${id}`),
  // POST /api/counsel/tickets — leadsToCounselor 리프에서 상담원 연결 요청(티켓 생성)
  createTicket: (request: CounselTicketCreateRequest) =>
    api.post<CounselTicketSummaryResponse>('/counsel/tickets', request),
  // GET /api/counsel/tickets/{id}/messages — 티켓 대화 전체 메시지
  getMessages: (ticketId: number) =>
    api.get<ChatMessageResponse[]>(`/counsel/tickets/${ticketId}/messages`),
  // GET /api/counsel/tickets/{id} — 티켓 단건 상태 조회(#1506). 소켓 재연결 시 WS push를 놓친 배정/종료
  // 상태를 REST로 백필하는 폴백 용도(useChatBot 참고).
  getTicket: (ticketId: number) =>
    api.get<CounselTicketSummaryResponse>(`/counsel/tickets/${ticketId}`),
  // GET /api/counsel/tickets/{id}/export — 대화 내보내기(.txt). 파일명은 Content-Disposition
  // 헤더로 오므로 blob 응답을 그대로 반환해 호출부(다운로드 유틸)가 헤더+본문을 함께 처리한다.
  // shared axios 인터셉터가 blob 응답은 envelope unwrap을 건너뛰도록 처리돼 있다(axios.ts 참고).
  exportConversation: (ticketId: number) =>
    api.get<Blob>(`/counsel/tickets/${ticketId}/export`, { responseType: 'blob' }),
  // 상담원 콘솔(#1001, HAJA-495) — 아래 3개는 COUNSELOR/PLATFORM_ADMIN 전용 엔드포인트.
  // GET /api/counsel/tickets — 배정 대기열 조회
  getQueue: (filters: CounselQueueFilters = {}) =>
    api.get<PageResponse<CounselTicketSummaryResponse>>('/counsel/tickets', { params: filters }),
  // POST /api/counsel/tickets/{id}/assign — 클레임. 동시 클레임 시 409
  // COUNSEL_SESSION_ASSIGNMENT_CONFLICT(낙관적 락 경합) — 호출부(useCounselorQueue)가
  // err.status===409로 분기해 안내 메시지 + 큐 새로고침을 처리한다.
  assign: (ticketId: number) =>
    api.post<CounselTicketDetailResponse>(`/counsel/tickets/${ticketId}/assign`),
  // POST /api/counsel/tickets/{id}/resolve — 상담 종료(#1000 후속: 담당 상담원뿐 아니라 티켓 소유
  // 고객 본인도 호출 가능, 백엔드 CounselTicketService#resolve 권한 완화와 짝).
  resolve: (ticketId: number) =>
    api.post<CounselTicketDetailResponse>(`/counsel/tickets/${ticketId}/resolve`),
  // GET /api/counsel/tickets/{id}/customer-history — 이 티켓 고객의 과거 상담 이력(현재 티켓 제외,
  // 담당 상담원 본인/PLATFORM_ADMIN만 조회 가능 — 백엔드 CounselTicketService#getCustomerHistory).
  getCustomerHistory: (ticketId: number) =>
    api.get<CounselTicketSummaryResponse[]>(`/counsel/tickets/${ticketId}/customer-history`),
  // GET /api/counsel/tickets/{id}/customer-history/{historyId}/messages — 이력 목록에서 클릭한 과거
  // 티켓의 대화 내용(담당자가 요청자와 달라도 ticketId 기준 정당한 접점이면 허용).
  getCustomerHistoryMessages: (ticketId: number, historyId: number) =>
    api.get<ChatMessageResponse[]>(`/counsel/tickets/${ticketId}/customer-history/${historyId}/messages`),
  // GET/PUT /api/counsel/tickets/{id}/note — 상담원 전용 비공개 메모(#1022/HAJA-504, 고객 비노출,
  // 티켓당 1개). 담당 상담원 본인만 조회/저장 가능(백엔드 CounselTicketNoteService).
  getNote: (ticketId: number) =>
    api.get<CounselTicketNoteResponse>(`/counsel/tickets/${ticketId}/note`),
  saveNote: (ticketId: number, request: CounselTicketNoteUpdateRequest) =>
    api.put<CounselTicketNoteResponse>(`/counsel/tickets/${ticketId}/note`, request),
  // 플랫폼 관리자 상담 관리(#1168) — GET /api/counsel/tickets/admin, PLATFORM_ADMIN 전용. 접수일
  // (createdAt) 기준 특정 날짜의 상담 티켓 전체를 조회한다(종료일 endedAt 아님 — 계획 확정 사항).
  getAdminTicketsByDate: (date: string, page = 0, size = 20) =>
    api.get<PageResponse<CounselTicketSummaryResponse>>('/counsel/tickets/admin', {
      params: { date, page, size },
    }),
};
