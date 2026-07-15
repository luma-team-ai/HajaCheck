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
// backend com.hajacheck.auth.entity.UserStatus와 1:1
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

export interface UpgradeInquiryResult {
  status: PlanStatus;
}
