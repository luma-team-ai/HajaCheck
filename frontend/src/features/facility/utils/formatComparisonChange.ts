/**
 * 회차 간 비교 KPI 증감을 화살표 포함 문자열로 변환합니다.
 * dashboard/utils/formatChangeRate.ts와 동일한 관용구이지만 feature 간 직접 import 금지(§1)라
 * facility에 로컬로 재정의한다 — "%"가 아니라 건수 단위라 접미사가 다르다.
 * @param changeValue - 증감(부호 포함), 양수=증가/악화 방향
 * @returns 화살표 포함 표시 문자열 (예: 8 → "↑8", -5 → "↓5")
 */
export function formatComparisonChange(changeValue: number): string {
  if (changeValue === 0) return '0';
  const arrow = changeValue > 0 ? '↑' : '↓';
  return `${arrow}${Math.abs(changeValue)}`;
}

/**
 * 증감 방향에 따른 색상 클래스 — 양수(증가)=빨강, 음수(감소)=초록.
 * (개선/조치 완료처럼 값이 줄어드는 것이 좋은 지표여도 규칙은 부호로만 판단한다 — 이슈 #489 스펙.)
 */
export function getComparisonChangeColorClass(changeValue: number): string {
  if (changeValue > 0) return 'text-[#dc2626]';
  if (changeValue < 0) return 'text-[#16a34a]';
  return 'text-text-muted';
}