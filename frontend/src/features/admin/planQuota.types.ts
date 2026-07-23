// 관리자 > 플랜·쿼터 관리 도메인 타입 — Figma node-id 1197-3519
// "hajaCheck Business Admin - 플랜·쿼터 관리 워크스페이스" 기준.
// 백엔드 계약(docs/api-contract/contract.md)에 아직 /admin/plan-quota 스펙이 없어, 아래 코드값은
// 화면 요구사항 기준의 선제 정의다(사용자 관리 features/admin/types.ts와 동일한 전략).
// plan 값은 사용자 관리와 동일한 plan_name_type을 재사용한다 — 한쪽만 바뀌면 라벨 조회가 어긋난다.
//
// 스코프 정정(2026-07-21): Figma 시안의 "Hyundai Motors/TechCorp Inc." 등은 여러 회사를 나열한
// 임의 목업 데이터일 뿐, 실제 요구사항은 **로그인한 관리자 소속 회사 하나**로 한정된다. 즉 이 화면의
// "사용자"는 다른 고객사가 아니라 **내 회사에 등록된 멤버(개인 계정)**를 뜻한다. plan/quotaLimit은
// 회사가 구독한 단일 플랜에서 나오는 값이라 모든 행에서 동일하고(company_memberships로 상속),
// quotaUsed만 멤버별로 다르다 — 회사 공용 월 한도를 멤버들이 나눠 쓰는 구조.
import type { AdminUserPlan } from './types';

export type { AdminUserPlan };

/** 내 회사 소속 멤버(개인 계정) 1명의 월 분석 쿼터 사용 현황 한 행 */
export interface PlanQuotaUser {
  id: number;
  /** 멤버 표시명 (예: "김민준") */
  name: string;
  email: string;
  /** 회사가 구독한 플랜 — 모든 행에서 동일(회사 단위 구독), 활성 구독이 없으면 null */
  plan: AdminUserPlan | null;
  /** 이 멤버가 이번 달 분석한 이미지 장수 */
  quotaUsed: number;
  /** 회사 플랜의 월 분석 한도(장) — 모든 행에서 동일한 공용 한도. null = 무제한 */
  quotaLimit: number | null;
}

export interface PlanQuotaStats {
  /** 내 회사의 활성 멤버 수 (KPI 카드 1) */
  activeUsers: number;
  /** 내 회사 전체 쿼터 사용률(%) — 멤버 사용량 합계/공용 한도 (KPI 카드 2) */
  totalQuotaUsagePercent: number;
  /**
   * 로그인한 관리자(company_id)의 현재 구독 플랜 — "현재 플랜" 카드는 이 값 하나로 렌더한다.
   * 표에서 어떤 멤버 행을 보고 있는지와 무관하게 항상 내 회사의 플랜을 보여준다(#508 확정).
   * 활성 구독이 없으면 null.
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

// GET /api/admin/plans 응답 — plans 테이블 원본(요금·한도)을 그대로 반환한다. "현재 플랜" 카드는
// 이 값으로 가격·기능 한도를 렌더링해, 프론트에 요금 하드코딩을 두지 않는다(계약: AdminPlanItem 1:1).
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
  /** plans.price_monthly는 DDL상 nullable — null이면 가격 미정(openapi AdminPlanItem과 1:1) */
  priceMonthly: number | null;
}

export interface AdminPlanCatalogResponse {
  plans: AdminPlanCatalogItem[];
}
