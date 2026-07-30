import type { AdminUserPlan } from './types';

// "플랜 정책 설정" 모달 도메인 타입 — Figma 화면(2026-07-23 첨부 시안) 기준. 백엔드 정책 저장 API가
// 아직 없어(플랜 정책 설정 버튼의 클릭 동작 자체가 #625 시점엔 보류) 화면 세션 동안만 값을 들고
// 있는 로컬 폼 상태로 둔다. 실제 저장 API 계약이 정해지면 이 타입을 요청/응답 DTO에 맞춰 조정한다.

/** 플랜 1개(FREE/STANDARD/ENTERPRISE)에 대한 정책 값 한 벌 */
export interface PlanPolicyValues {
  /** 월 구독 가격 — 숫자 문자열(예: "49000"). "0"이면 무료 */
  priceMonthly: string;
  /** 최대 등록 시설 수 — 숫자 문자열. 빈 문자열("")이면 무제한(plans.max_facilities = NULL과 대응) */
  maxFacilities: string;
  /** 최대 월 분석 가능 횟수 — 숫자 문자열. 빈 문자열("")이면 협의(plans.max_monthly_analyses = NULL과 대응) */
  maxMonthlyAnalyses: string;
  /** 최대 사용자 좌석 수 — 숫자 문자열. 빈 문자열("")이면 무제한(plans.max_seats = NULL과 대응) */
  maxSeats: string;
  /** 보고서에 워터마크를 표시할지 여부 */
  hasPdfWatermark: boolean;
  /** 전문 상담사 연결을 제공할지 여부 */
  hasCounselorAccess: boolean;
}

export type PlanPolicyForm = Record<AdminUserPlan, PlanPolicyValues>;
