const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * 발생 시각을 상대 경과 시간 문자열로 변환합니다. ("2시간 전", "3일 전" 등)
 * @param occurredAt - ISO 8601 날짜 문자열
 * @param now - 기준 시각 (테스트 용이성을 위해 주입, 기본값 현재 시각)
 * @returns 상대 경과 시간 문자열
 */
export function formatElapsedTime(occurredAt: string, now: Date = new Date()): string {
  const diffMs = now.getTime() - new Date(occurredAt).getTime();

  if (diffMs < MINUTE_MS) return '방금 전';
  if (diffMs < HOUR_MS) return `${Math.floor(diffMs / MINUTE_MS)}분 전`;
  if (diffMs < DAY_MS) return `${Math.floor(diffMs / HOUR_MS)}시간 전`;
  return `${Math.floor(diffMs / DAY_MS)}일 전`;
}
