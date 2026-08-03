// 플랫폼 관리자 > 플랜·쿼터 관리 도메인 타입. Figma node-id 1206-2639(플랫폼 관리자 기준 화면) —
// 기업 관리자용 features/admin/planQuota.types.ts(#508)의 사이드 "현재 플랜" 카드형 레이아웃과 달리,
// 사용자별 플랜·남은 기간·상태를 한 표에 담는 구조라 타입을 이 화면 기준으로 다시 정의한다(#625).
// plan 값은 features/platform-admin/types.ts(#577)의 AdminUserPlan을 그대로 재사용한다.
import type { AdminUserPlan } from './types';

export type { AdminUserPlan };

/** 플랜 상태 배지 — 남은 기간에 대한 백엔드 판정 결과를 그대로 받는다(프론트에서 임계값 재계산하지 않음) */
export type PlanQuotaUserStatus = 'ACTIVE' | 'WARNING' | 'EXPIRED';

/** 전사 사용자 1명의 월 분석 쿼터 사용 현황 한 행 */
export interface PlanQuotaUser {
  id: number;
  /** 사용자 표시명 (예: "김민준") */
  name: string;
  email: string;
  /** 소속 회사 id — 회사 미소속(개인 계정)이면 null */
  companyId: number | null;
  /** 소속 회사명 — 회사 미소속(개인 계정)이면 null */
  companyName: string | null;
  /** 소속 회사가 구독한 플랜 — 활성 구독이 없으면 null */
  plan: AdminUserPlan | null;
  /**
   * 소속 회사가 이번 달 분석한 이미지 장수 — 쿼터는 회사 단위로 풀링되어 같은 회사 소속 사용자는 전부
   * 같은 값을 본다(사용자 개인별 소비량이 아니다, #1407 후속).
   */
  quotaUsed: number;
  /** 소속 회사 플랜의 월 분석 한도(장). null = 무제한 */
  quotaLimit: number | null;
  /**
   * 플랜 만료까지 남은 일수. null이면 만료됨(더 이상 유효하지 않음).
   * 백엔드 /api/platform-admin/plans-quota(#624) 계약 확정 전까지 프론트 목데이터로만 채운다.
   */
  remainingDays: number | null;
  /** 백엔드 계약 확정 전까지 목데이터로만 채운다(#624) */
  status: PlanQuotaUserStatus;
}

export interface PlanQuotaStats {
  /** 전사 활성 사용자 수 (KPI 카드 1) */
  activeUsers: number;
  /**
   * 전사 평균 쿼터 사용률(%) — 한도가 있는(무제한 아닌) 구독들의 회사별 사용률 평균 (KPI 카드 2).
   * 무제한(ENTERPRISE 등) 플랜은 "사용량 ÷ 한도"가 정의되지 않아 이 평균에 포함되지 않는다
   * (unlimitedPlanUsageTotal 참고, #1407).
   */
  totalQuotaUsagePercent: number;
  /**
   * 무제한 플랜 구독들의 이번 달 사용량 합계(장) — totalQuotaUsagePercent 평균에서 제외되는 사용량이
   * 화면에서 사라지지 않도록 KPI 카드 2에 보조 텍스트로 병기한다(#1407).
   */
  unlimitedPlanUsageTotal: number;
}

export interface PlanQuotaListParams {
  page: number;
  size: number;
  keyword?: string;
  /** 소속 회사 구독 플랜으로 좁혀본다(회사 단위 필터) */
  plan?: AdminUserPlan;
}

export interface PlanQuotaListResponse {
  content: PlanQuotaUser[];
  page: number;
  size: number;
  totalElements: number;
  /** KPI 카드 값 — 검색어와 무관한 전체 기준 집계 */
  stats: PlanQuotaStats;
}
