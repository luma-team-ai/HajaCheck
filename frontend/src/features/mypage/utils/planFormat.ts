import type { PlanName, PlanStatus } from '../types';

// 요금제 한도·사용량 표기 유틸(HAJA-185) — limits.max_*가 null이면 "무제한"(contract.md)

export function formatLimit(limit: number | null): string {
  return limit === null ? '무제한' : limit.toLocaleString();
}

// 무제한(null)이거나 한도가 0이면 퍼센트를 계산할 수 없음 — 0~100으로 clamp
export function usagePercent(used: number, limit: number | null): number | null {
  if (limit === null || limit <= 0) return null;
  return Math.min(100, Math.round((used / limit) * 100));
}

// 사용량 80% 이상이면 경고 색 표시(handoff 완료 기준)
export function isUsageWarning(percent: number | null): boolean {
  return percent !== null && percent >= 80;
}

export function formatPriceMonthly(price: number): string {
  return `₩${price.toLocaleString()}/월`;
}

export const PLAN_NAME_LABEL: Record<PlanName, string> = {
  FREE: 'Free',
  STANDARD: 'Standard',
  ENTERPRISE: 'Enterprise',
};

export const PLAN_STATUS_LABEL: Record<PlanStatus, string> = {
  ACTIVE: '이용중',
  EXPIRED: '만료',
  UPGRADE_REQUESTED: '업그레이드 요청중',
};
