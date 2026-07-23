// 마이페이지 — 내 플랜·사용량·좌석(HAJA-185, #212)
// docs/api-contract/contract.md "마이페이지 — 내 플랜·사용량·좌석 — Contract v1"과 1:1
// backend/src/main/java/com/hajacheck/membership/** (PR #211, HAJA-177) 응답 shape 기준

export type PlanName = 'FREE' | 'STANDARD' | 'ENTERPRISE';
export type PlanStatus = 'ACTIVE' | 'EXPIRED' | 'UPGRADE_REQUESTED';

export interface MyPlanInfo {
  name: PlanName;
  priceMonthly: number;
  status: PlanStatus;
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

export interface UpgradeInquiryResult {
  status: PlanStatus;
}

// contract.md 추가 ErrorCode(마이페이지) — error.code 비교를 이 상수로 통일해 오타 시 컴파일 에러가 나게 한다.
// (shared/api/types.ts의 ApiError.code는 앱 전역 에러코드를 아우르는 plain string이라 여기서 좁힐 수 없음 —
// 대신 이 객체의 프로퍼티를 통해서만 비교하게 해 오타를 컴파일 타임에 잡는다.)
export const MYPAGE_ERROR_CODE = {
  PLAN_NOT_FOUND: 'PLAN_NOT_FOUND',
  PLAN_FORBIDDEN: 'PLAN_FORBIDDEN',
} as const;

export type MyPageErrorCode = (typeof MYPAGE_ERROR_CODE)[keyof typeof MYPAGE_ERROR_CODE];

// ---- 마이페이지 — 내 점검 이력 / 보고서 (HAJA-366, #668) ----
// 이 화면을 뒷받침하는 BE API가 전혀 없다(grep 0건) — Figma 시안 기준 mock 우선 구현.
// InspectionHistoryStatus는 실 InspectionStatus(점검 회차 상태)와 이름만 다를 뿐 아직 매핑이
// 확정되지 않았다(후속 BE #24/#210 계열 연동 시 실 enum과 맞춰 재정의될 수 있음) — 그래서
// dashboard의 InspectionStatus를 그대로 쓰지 않고 이 feature 전용 타입으로 별도 정의한다.
export type InspectionHistoryRole = 'INSPECTOR' | 'OWNER'; // 점검자 / 소유자(점검 회사 스코프 정책과 동일 구분)
export type InspectionHistoryStatus = 'REVIEW_DONE' | 'REVIEW_PENDING' | 'ANALYZING'; // 검수완료 / 검수대기 / 분석중

export interface InspectionHistoryRow {
  id: number;
  facilityName: string;
  round: string; // 회차 표기, 예: '24-03'
  inspectedAt: string; // 점검일 표기, 예: '2024.03.15' (BE 미구현이라 포맷 확정 전 — 문자열 그대로 표시)
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
  title: string;
  issuedAt: string; // 예: '2024.03.16'
  fileSizeLabel: string; // 예: '1.2MB'
  gradeDots: ReportGradeDotColor[];
}
