// 마이페이지 — 내 플랜·사용량·좌석(HAJA-185, #212)
// docs/api-contract/contract.md "마이페이지 — 내 플랜·사용량·좌석 — Contract v1"과 1:1
// backend/src/main/java/com/hajacheck/membership/** (PR #211, HAJA-177) 응답 shape 기준

export type PlanName = 'FREE' | 'STANDARD' | 'ENTERPRISE';
export type PlanStatus = 'ACTIVE' | 'EXPIRED' | 'UPGRADE_REQUESTED';

export interface MyPlanInfo {
  name: PlanName;
  priceMonthly: number;
  status: PlanStatus;
  // Figma 리디자인(node 1463-2786, #712) — BE #711/PR#714에서 확정된 GET /me/plan 확장 필드.
  // nextBillingDate: 유료 플랜만(FREE는 null). businessVerified: 회사 구독=boolean, 개인 구독(companyId=null)=null.
  nextBillingDate: string | null;
  businessVerified: boolean | null;
}

// limits.max_* 는 무제한이면 null 그대로 반환(contract.md) — FE에서 "무제한" 표기
export interface MyPlanLimits {
  maxFacilities: number | null;
  maxMonthlyAnalyses: number | null;
  maxSeats: number | null;
}

export interface MyPlanUsage {
  facilityCount: number;
  analyzedImageCount: number;
  seatCount: number;
  period: string; // 이번 달 1일(ISO date) — 백엔드 LocalDate 직렬화
}

export interface MyPlan {
  plan: MyPlanInfo;
  limits: MyPlanLimits;
  usage: MyPlanUsage;
}

// backend com.hajacheck.auth.entity.Role과 1:1
export type SeatMemberRole = 'ADMIN' | 'INSPECTOR' | 'USER' | 'COUNSELOR';
// backend com.hajacheck.auth.entity.UserStatus 값과 1:1(ACTIVE/SUSPENDED).
// 초대는 초대코드로 일원화됐다(관리자 AdminUsersPage → InviteCodeModal, 가입측 InviteCodePage) —
// 좌석 섹션은 조회 전용이라 '초대됨' 같은 프론트 전용 상태가 필요 없다(#1045, HAJA-508).
export type SeatMemberStatus = 'ACTIVE' | 'SUSPENDED';

export interface SeatMember {
  userId: number;
  name: string;
  email: string;
  role: SeatMemberRole;
  status: SeatMemberStatus;
}

export interface SeatsInfo {
  used: number;
  limit: number | null;
  members: SeatMember[];
}

// contract.md 추가 ErrorCode(마이페이지) — error.code 비교를 이 상수로 통일해 오타 시 컴파일 에러가 나게 한다.
// (shared/api/types.ts의 ApiError.code는 앱 전역 에러코드를 아우르는 plain string이라 여기서 좁힐 수 없음 —
// 대신 이 객체의 프로퍼티를 통해서만 비교하게 해 오타를 컴파일 타임에 잡는다.)
// PLAN_ACTIVE_SUBSCRIPTION_CONFLICT: POST /me/plan/orders(구 /me/plan/checkout)뿐 아니라
// POST /me/payments/confirm(결제 승인)에서도 발생한다(백엔드 #988 리뷰 픽스로 계약 확장,
// 2026-07-27) — 이미 그 플랜인데 확정을 시도하면 PG 청구 전에 거절된다. "이미 해당 플랜을
// 이용 중입니다" 취지로 안내하고 플랜 정보를 새로고침하면 된다(재시도 유도 아님).
// PAYMENT_* 5종은 #989 신규 — POST /me/plan/orders(주문 생성)와 POST /me/payments/confirm(결제 승인)
// 양쪽에서 발생할 수 있다. 비소유자 주문 접근은 PAYMENT_FORBIDDEN(403)이 아니라
// PAYMENT_ORDER_NOT_FOUND(404)로 온다(#988 리뷰 픽스) — PAYMENT_FORBIDDEN은 이 경로에서 더 이상
// 발생하지 않지만 계약상 값 자체는 유지한다.
// PAYMENT_PLAN_APPLY_PENDING: 결제(PG 승인)는 성공했으나 플랜 반영(소속 변경 등)만 실패한 상태
// (#988 리뷰 픽스, 2026-07-27). **"결제 실패"로 취급 금지** — 사용자가 재결제하면 환불 불가한
// 중복 청구가 된다. "결제는 완료, 플랜 반영 처리 중" 취지로만 안내하고 재시도/재결제 버튼을
// 노출하지 않는다.
export const MYPAGE_ERROR_CODE = {
  PLAN_NOT_FOUND: 'PLAN_NOT_FOUND',
  PLAN_FORBIDDEN: 'PLAN_FORBIDDEN',
  PLAN_ACTIVE_SUBSCRIPTION_CONFLICT: 'PLAN_ACTIVE_SUBSCRIPTION_CONFLICT',
  PAYMENT_ORDER_NOT_FOUND: 'PAYMENT_ORDER_NOT_FOUND',
  PAYMENT_FORBIDDEN: 'PAYMENT_FORBIDDEN',
  PAYMENT_AMOUNT_MISMATCH: 'PAYMENT_AMOUNT_MISMATCH',
  PAYMENT_GATEWAY_ERROR: 'PAYMENT_GATEWAY_ERROR',
  PLAN_DOWNGRADE_CONFIRMATION_REQUIRED: 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED',
  PAYMENT_PLAN_APPLY_PENDING: 'PAYMENT_PLAN_APPLY_PENDING',
} as const;

export type MyPageErrorCode = (typeof MYPAGE_ERROR_CODE)[keyof typeof MYPAGE_ERROR_CODE];

// ---- 토스페이먼츠 결제창 연동(#989, HAJA-490) ----
// handoff "API 계약" 절과 1:1. 웹훅·환불/취소 UI·정기결제·간편결제/계좌이체는 범위 밖(handoff 배경).

// POST /me/plan/orders 응답 — 결제창(requestPayment)에 그대로 넘길 주문 정보.
// amount는 서버가 계산한 금액이 source of truth다(구 PlanCheckoutModal의 UPGRADE_PLAN_PRICE
// 하드코딩을 대체 — 클라이언트에서 금액을 추정/표시하지 않는다).
// listPrice/credit은 상향 결제 잔여 기간 일할 크레딧(#1146, HAJA-550) 노출용 — listPrice - credit ===
// amount가 항상 성립한다(서버가 불변식으로 보장, PaymentWriter#requireOrderInvariant). 크레딧이
// 없으면(주기 정보 없음·만료 경과·FREE→유료·실제 결제 이력 없음) 0으로 온다.
export interface PlanOrder {
  orderId: string;
  planName: PlanName;
  listPrice: number;
  credit: number;
  amount: number;
  orderName: string;
}

// GET /me/payments 항목 status — 최신순 결제 내역.
export type PaymentStatus = 'READY' | 'PAID' | 'FAILED' | 'CANCELED';

export interface PaymentHistoryItem {
  id: number;
  orderId: string;
  planName: PlanName;
  amount: number;
  status: PaymentStatus;
  method: string | null;
  approvedAt: string | null; // ISO datetime, 미승인(READY/FAILED)이면 null
  receiptUrl: string | null;
}

// ---- 마이페이지 — 내 점검 이력 / 보고서 (HAJA-366/#668, BE 연동 #844/HAJA-442) ----
// BE(mypage.dto.*)와 1:1 — 표시용 문자열(회차 조립·날짜 포맷·파일크기·보고서 제목)은 전부 원시값으로
// 받고 프론트(utils/myInspectionsFormat.ts)가 조립한다(전역 컨벤션: 표시 포맷은 클라이언트 책임).
// InspectionHistoryStatus는 실 InspectionStatus(점검 회차 상태, 6종)를 BE가 화면 3종으로 매핑해 내려준다
// (com.hajacheck.mypage.dto.MyInspectionDisplayStatus) — dashboard의 InspectionStatus와는 별개 타입.
export type InspectionHistoryRole = 'INSPECTOR' | 'OWNER'; // 점검자 / 소유자(점검 회사 스코프 정책과 동일 구분)
export type InspectionHistoryStatus = 'REVIEW_DONE' | 'REVIEW_PENDING' | 'ANALYZING'; // 검수완료 / 검수대기 / 분석중

export interface InspectionHistoryRow {
  id: number;
  facilityName: string;
  roundNo: number; // 회차 정수 그대로 — '24-03' 조립은 utils/myInspectionsFormat.ts#formatRoundLabel
  inspectionDate: string; // ISO 'yyyy-MM-dd'(LocalDate) — '2024.03.15' 표기는 formatInspectionDate
  role: InspectionHistoryRole;
  defectCount: number;
  status: InspectionHistoryStatus;
}

export interface MyInspectionsSummary {
  participatedCount: number; // 참여 점검(회차)
  reviewConfirmedCount: number; // 검수 확정
  issuedReportCount: number; // 발급 보고서
  inProgressCount: number; // 진행 중
}

// 보고서 카드 등급 dots 색상 — A~E 등급 문자가 아니라 신호등 색(빨강/주황/초록) 3색만 쓴다(Figma 시안).
export type ReportGradeDotColor = 'RED' | 'ORANGE' | 'GREEN';

export interface MyReportCard {
  id: number;
  inspectionId: number;
  facilityName: string; // title 대신 원시값 — '[{yy-RR}] {facilityName} 점검 보고서' 조립은 formatReportTitle
  roundNo: number;
  issuedAt: string; // ISO datetime(LocalDateTime, Report.updatedAt 근사) — '2024.03.16' 표기는 formatIssuedDate
  fileSizeBytes: number | null; // null이면 PDF 조회 실패 — 화면에서 크기 표시 자체를 감춘다
  gradeDots: ReportGradeDotColor[];
}

// ---- 마이페이지 — 비밀번호 변경(#1316, HAJA-602) ----
// PATCH /api/users/me/password — BE #1315와 병렬 구현(handoff docs/_local/handoff/password-change-fe-1316-next.md
// 가 계약 소스). 성공 시 서버가 현재 세션을 무효화한다. status(401)만으로는 "현재 비밀번호 불일치"와
// "세션 만료·미인증"을 구분할 수 없어(둘 다 401) code로 분기해야 한다 — 400: 소셜 전용 계정·정책
// 위반·신구 동일 / 429: 요청 과다.
export interface PasswordChangeRequest {
  currentPassword: string;
  newPassword: string;
}

// BE(#1315) 실코드 확인 완료(2026-07-30) — RestAuthenticationEntryPoint → ErrorCode.UNAUTHORIZED
// (미인증·세션 만료), PasswordChangeService:90 → ErrorCode.AUTH_INVALID_CREDENTIALS(현재 비밀번호
// 불일치). 이 두 코드로 401을 화이트리스트 분기한다 — code==='UNAUTHORIZED'일 때만 세션 만료로 보고
// 로그아웃시키고, 그 외 401(AUTH_INVALID_CREDENTIALS 포함 + 알 수 없는 코드)은 전부 "현재 비밀번호
// 오류"로 취급해 필드 인라인 에러만 보여준다(모르는 401을 세션 만료로 오분류해 정상 재시도까지
// 강제 로그아웃시키는 게 더 나쁜 방향이므로 — 코드 리뷰 P2-2). mypageApi.handlers.ts(목)·
// PasswordChangeSection(컴포넌트)·테스트가 전부 이 상수를 참조해 하드코딩 문자열이 흩어지지
// 않게 한다.
export const PASSWORD_CHANGE_ERROR_CODE = {
  SESSION_EXPIRED: 'UNAUTHORIZED',
  INVALID_CREDENTIALS: 'AUTH_INVALID_CREDENTIALS',
  PASSWORD_NOT_SET: 'AUTH_PASSWORD_NOT_SET',
  PASSWORD_UNCHANGED: 'AUTH_PASSWORD_UNCHANGED',
  TOO_MANY_REQUESTS: 'AUTH_TOO_MANY_REQUESTS',
} as const;

export type PasswordChangeErrorCode =
  (typeof PASSWORD_CHANGE_ERROR_CODE)[keyof typeof PASSWORD_CHANGE_ERROR_CODE];
