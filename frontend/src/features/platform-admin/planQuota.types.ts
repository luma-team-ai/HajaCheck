// 플랫폼 관리자 > 플랜·쿼터 관리 도메인 타입 — features/admin/planQuota.types.ts(#508 기업 관리자
// 플랜·쿼터 관리)를 그대로 옮긴 것(#625). 기업 관리자 화면은 GET /api/admin/plan-quota(회사 스코프)를
// 쓰고 이 화면은 GET /api/platform-admin/plans-quota(전체 회사 스코프, PLATFORM_ADMIN 전용)를 쓴다.
// plan 값은 features/platform-admin/types.ts(#577)의 AdminUserPlan을 그대로 재사용한다.
import type { AdminUserPlan } from './types';

export type { AdminUserPlan };

/** 전사 사용자 1명의 월 분석 쿼터 사용 현황 한 행 */
export interface PlanQuotaUser {
  id: number;
  /** 사용자 표시명 (예: "김민준") */
  name: string;
  email: string;
  /** 소속 회사가 구독한 플랜 — 활성 구독이 없으면 null */
  plan: AdminUserPlan | null;
  /** 이 사용자가 이번 달 분석한 이미지 장수 */
  quotaUsed: number;
  /** 소속 회사 플랜의 월 분석 한도(장). null = 무제한 */
  quotaLimit: number | null;
}

export interface PlanQuotaStats {
  /** 전사 활성 사용자 수 (KPI 카드 1) */
  activeUsers: number;
  /** 전사 전체 쿼터 사용률(%) — 사용량 합계/공용 한도 (KPI 카드 2) */
  totalQuotaUsagePercent: number;
  /**
   * "현재 플랜" 카드에 표시할 플랜 — 기업 관리자 화면은 로그인 관리자의 회사 플랜 고정값이지만,
   * 플랫폼 관리자는 전사 스코프라 특정 회사 하나로 고정되지 않는다. 백엔드 API 확정 전까지는
   * 원본과 동일한 형태(단일 값)로 유지하고, 실제 의미는 API 계약 확정 시 재확인한다(#624 참고).
   */
  companyPlan: AdminUserPlan | null;
}

export interface PlanQuotaListParams {
  page: number;
  size: number;
  keyword?: string;
}

export interface PlanQuotaListResponse {
  content: PlanQuotaUser[];
  page: number;
  size: number;
  totalElements: number;
  /** KPI 카드 값 — 검색어와 무관한 전체 기준 집계 */
  stats: PlanQuotaStats;
}

/** 플랜 카드 한 줄 기능 항목 */
export interface PlanFeature {
  label: string;
  /** 해당 플랜에 포함되는 기능이면 true(체크), 미포함이면 false(회색 처리) */
  included: boolean;
}

/** "현재 플랜" 카드에 표시할 플랜별 상세 정의 */
export interface PlanDetail {
  name: AdminUserPlan;
  /** 플랜 부제(예: "체험·개인 사용자") */
  tagline: string;
  /** 월 요금(원). 0이면 무료, null이면 가격 미정(plans.price_monthly가 DDL상 nullable) */
  priceMonthly: number | null;
  features: PlanFeature[];
  /** 카드 하단 CTA 버튼 라벨 */
  ctaLabel: string;
}

// GET /api/platform-admin/plans 응답 — plans 테이블 원본(요금·한도)을 그대로 반환한다.
export interface AdminPlanCatalogItem {
  id: number;
  name: AdminUserPlan;
  /** null = 무제한 */
  maxFacilities: number | null;
  /** null = 무제한 */
  maxMonthlyAnalyses: number | null;
  /** null = 무제한, 1 이하 = 추가 좌석 없음(1인 계정) */
  maxSeats: number | null;
  hasPdfWatermark: boolean;
  hasCounselorAccess: boolean;
  hasAiAddon: boolean;
  /** plans.price_monthly는 DDL상 nullable — null이면 가격 미정 */
  priceMonthly: number | null;
}

export interface AdminPlanCatalogResponse {
  plans: AdminPlanCatalogItem[];
}
