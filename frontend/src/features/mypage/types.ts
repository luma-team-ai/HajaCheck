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
// backend com.hajacheck.auth.entity.UserStatus 값(ACTIVE/SUSPENDED) + 프론트 전용 'INVITED'.
// 'INVITED'(초대됨)는 실 UserStatus에 없는 값이다 — 좌석 초대 기능이 아직 백엔드에 없어(후속 #24/#210)
// 실 API 응답은 절대 이 값을 반환하지 않는다. 마이페이지 '내 정보'(#659) 좌석 섹션에서 데모 표시
// 용도로만 mocks/mypage.mock.ts의 mockInvitedSeatMember를 통해 주입한다.
export type SeatMemberStatus = 'ACTIVE' | 'SUSPENDED' | 'INVITED';

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
// PLAN_ACTIVE_SUBSCRIPTION_CONFLICT: POST /me/plan/orders(구 /me/plan/checkout) 동시 요청 경합(409) —
// 토스페이먼츠 연동(#989, HAJA-490)으로 모의 결제 엔드포인트가 실 결제창 흐름으로 교체되며 발생 경로가
// checkout → orders/confirm으로 바뀌었다(#712/PR#714 계약 당시의 원래 발생 지점).
// PAYMENT_* 5종은 #989 신규 — POST /me/plan/orders(주문 생성)와 POST /me/payments/confirm(결제 승인)
// 양쪽에서 발생할 수 있다.
export const MYPAGE_ERROR_CODE = {
  PLAN_NOT_FOUND: 'PLAN_NOT_FOUND',
  PLAN_FORBIDDEN: 'PLAN_FORBIDDEN',
  PLAN_ACTIVE_SUBSCRIPTION_CONFLICT: 'PLAN_ACTIVE_SUBSCRIPTION_CONFLICT',
  PAYMENT_ORDER_NOT_FOUND: 'PAYMENT_ORDER_NOT_FOUND',
  PAYMENT_FORBIDDEN: 'PAYMENT_FORBIDDEN',
  PAYMENT_AMOUNT_MISMATCH: 'PAYMENT_AMOUNT_MISMATCH',
  PAYMENT_GATEWAY_ERROR: 'PAYMENT_GATEWAY_ERROR',
  PLAN_DOWNGRADE_CONFIRMATION_REQUIRED: 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED',
} as const;

export type MyPageErrorCode = (typeof MYPAGE_ERROR_CODE)[keyof typeof MYPAGE_ERROR_CODE];

// ---- 토스페이먼츠 결제창 연동(#989, HAJA-490) ----
// handoff "API 계약" 절과 1:1. 웹훅·환불/취소 UI·정기결제·간편결제/계좌이체는 범위 밖(handoff 배경).

// POST /me/plan/orders 응답 — 결제창(requestPayment)에 그대로 넘길 주문 정보.
// amount는 서버가 계산한 금액이 source of truth다(구 PlanCheckoutModal의 UPGRADE_PLAN_PRICE
// 하드코딩을 대체 — 클라이언트에서 금액을 추정/표시하지 않는다).
export interface PlanOrder {
  orderId: string;
  planName: PlanName;
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
