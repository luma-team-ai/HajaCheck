import { UNLIMITED_LABEL } from '../planQuota.constants';

// 플랜·쿼터 관리 사용량 계산 유틸 — 한도(null=무제한)와 0 나눗셈을 안전하게 처리한다.

/**
 * 월 분석 쿼터 사용률(%). 무제한(limit=null)이거나 한도가 0 이하면 계산 불가(null 반환).
 * 0~100으로 clamp — 초과 사용분이 바를 넘어 그려지지 않도록 한다.
 */
export function quotaPercent(used: number, limit: number | null): number | null {
  if (limit === null || limit <= 0) {
    return null;
  }
  return Math.min(100, Math.round((used / limit) * 100));
}

/** 한도 표기 — 무제한이면 "무제한", 아니면 천단위 구분 숫자 */
export function formatQuotaLimit(limit: number | null): string {
  return limit === null ? UNLIMITED_LABEL : limit.toLocaleString('ko-KR');
}

/** 사용량 표기 — 천단위 구분 숫자 */
export function formatQuotaUsed(used: number): string {
  return used.toLocaleString('ko-KR');
}
