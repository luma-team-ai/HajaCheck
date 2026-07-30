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
  // 상담원이 배정되기 전(WAITING)에는 null. 항상 내려오는 필드이므로 optional이 아니라
  // null만 허용하면 되지만, 과거 미배포 응답과의 호환을 위해 optional도 함께 열어둔다.
  counselorName?: string | null;
  status: CounselTicketStatus;
  queuePosition: number | null;
  createdAt: string; // ISO LocalDateTime
  // 플랫폼 관리자 상담 관리(#1168) — GET /api/counsel/tickets/admin 전용 응답에서만 채워지는
  // 고객 프로필 스냅샷. 기존 사용처(마이페이지/상담원 콘솔 대기열)의 응답엔 이 필드들이 없어도
  // optional이라 영향 없음.
  customerName?: string;
  customerEmail?: string;
  customerPlan?: string;
  customerJoinedAt?: string;
}

// GET /api/counsel/scenarios/roots, 자식 버튼 목록 — 시나리오 버튼(노드) 요약
export interface BotScenarioButtonResponse {
  id: number;
  category: string;
  buttonLabel: string;
  leadsToCounselor: boolean;
}

// GET /api/counsel/scenarios/{id} 응답 — 노드 상세(응답 텍스트 + 자식 버튼)
export interface BotScenarioNodeResponse {
  id: number;
  parentId: number | null;
  category: string;
  buttonLabel: string;
  responseText: string | null;
  leadsToCounselor: boolean;
  children: BotScenarioButtonResponse[];
}

// POST /api/counsel/tickets 요청
export interface CounselTicketCreateRequest {
  scenarioId: number;
}

export type ChatMessageSender = 'USER' | 'COUNSELOR' | 'BOT';

// GET /api/counsel/tickets/{id}/messages 응답 항목
export interface ChatMessageResponse {
  id: number;
  sessionId: number;
  sender: ChatMessageSender;
  content: string;
  attachmentUrl: string | null;
  // sender=COUNSELOR일 때만 채워짐(그 외에는 null)
  counselorName?: string | null;
  createdAt: string;
}

// 상담원 콘솔(#1001, HAJA-495) — GET /api/counsel/tickets(대기열, COUNSELOR/PLATFORM_ADMIN 전용)
// 쿼리 파라미터. /mine과 달리 사용자 자신의 상담이 아니라 배정 전 대기열 전체를 본다.
export interface CounselQueueFilters {
  // 미지정 시 백엔드 기본값 WAITING(대기열 조회 전용 스크린 등). 담당 상담(IN_PROGRESS)은
  // 스킬이 아니라 담당자 본인 여부로 좁혀진다(백엔드 CounselTicketService#findQueueForCounselor).
  status?: CounselTicketStatus;
  page?: number;
  size?: number;
}

// POST .../assign, .../resolve 응답 — 백엔드 CounselTicketResponse와 1:1(CounselTicketSummaryResponse
// 상위 호환: sessionId/endedAt이 추가된다). 목록 요약과 별개 타입인 이유는 백엔드가 실제로 별도 DTO를
// 반환하기 때문(CounselTicketController#assign/#resolve).
export interface CounselTicketDetailResponse extends CounselTicketSummaryResponse {
  sessionId: number | null;
  endedAt: string | null; // Instant, 진행 중이면 null
}

// GET/PUT .../note 응답 — 상담원 전용 비공개 메모(#1021/HAJA-503, 티켓당 1개, 고객 비노출).
// 메모가 아직 없으면 counselorId/content/updatedAt이 모두 null(백엔드 CounselTicketNoteResponse.empty).
export interface CounselTicketNoteResponse {
  ticketId: number;
  counselorId: number | null;
  content: string | null;
  updatedAt: string | null;
}

// PUT .../note 요청 — 빈 메모(공백/빈 문자열)도 허용한다(초기화 용도).
export interface CounselTicketNoteUpdateRequest {
  content: string;
}
