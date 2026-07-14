/**
 * 증감율을 화살표가 포함된 표시 문자열로 변환합니다. (예: 8 → "↗8%", -4 → "↘4%")
 * @param rate - 증감율 (%, 음수는 감소)
 * @returns 화살표 포함 표시 문자열
 */
export function formatChangeRate(rate: number): string {
  if (rate === 0) return '0%';
  const arrow = rate > 0 ? '↗' : '↘';
  return `${arrow}${Math.abs(rate)}%`;
}
