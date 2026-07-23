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
  /** 소속 회사가 구독한 플랜 — 활성 구독이 없으면 null */
  plan: AdminUserPlan | null;
  /** 이 사용자가 이번 달 분석한 이미지 장수 */
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
  /** 전사 평균 쿼터 사용률(%) — 사용량 합계/공용 한도 (KPI 카드 2) */
  totalQuotaUsagePercent: number;
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
