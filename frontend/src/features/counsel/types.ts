// 마이페이지 > 내 상담 이력 (#20, HAJA-33) — 백엔드 CounselController 응답 DTO와 1:1
// (docs 계약: GET /api/counsel/tickets/mine, GET /api/counsel/tickets/{id}/messages,
// GET /api/counsel/tickets/{id}/export)

// 백엔드 CounselStatus enum과 1:1
export type CounselTicketStatus = 'WAITING' | 'IN_PROGRESS' | 'RESOLVED' | 'OFFLINE_LEFT';

// 목록 조회 쿼리 파라미터 status — 'ALL'은 전체 조회 전용 값(서버가 그대로 지원)
export type CounselTicketStatusFilter = 'ALL' | CounselTicketStatus;

export interface CounselTicketListFilters {
  status?: CounselTicketStatusFilter;
  page?: number;
  size?: number;
}

// GET /api/counsel/tickets/mine 응답 항목
export interface CounselTicketSummaryResponse {
  id: number;
  ticketNumber: string; // "CS-20260725-014"
  category: string; // 최상위 카테고리 스냅샷, 예: "점검 결과서 관련"
  title: string; // 구체 제목 스냅샷, 예: "AI 분석 결과 등급 문의"
  userId: number;
  counselorId: number | null;
  // 백엔드에 지금 추가 중인 필드(counselorName) — 미배포 응답엔 없을 수 있어 optional 겸 null 허용
  counselorName?: string | null;
  status: CounselTicketStatus;
  queuePosition: number | null;
  createdAt: string; // ISO LocalDateTime
}

export type ChatMessageSender = 'USER' | 'COUNSELOR' | 'BOT';

// GET /api/counsel/tickets/{id}/messages 응답 항목
export interface ChatMessageResponse {
  id: number;
  sessionId: number;
  sender: ChatMessageSender;
  content: string;
  attachmentUrl: string | null;
  // sender=COUNSELOR일 때만 채워짐(백엔드 추가 중) — optional
  counselorName?: string | null;
  createdAt: string;
}
