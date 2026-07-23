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

// 다음 결제일 표기(HAJA-?, #712 Figma 리디자인) — BE는 LocalDate를 "YYYY-MM-DD" ISO로 직렬화해
// 내려준다(계약 그대로 표기). Date 파싱을 거쳐 시:분 등 예상 밖 포맷이 섞여 와도 안전하게
// YYYY-MM-DD로 정규화하고, 파싱 불가한 값은 원문을 그대로 반환한다(빈 화면보다 원문 노출이 낫다).
export function formatBillingDate(iso: string): string {
  const date = new Date(`${iso.slice(0, 10)}T00:00:00`);
  if (Number.isNaN(date.getTime())) return iso;

  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
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
