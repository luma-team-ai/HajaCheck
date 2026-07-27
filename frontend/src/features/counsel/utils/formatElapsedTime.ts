const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * 상담원 콘솔 채팅 목록(#1001, HAJA-495)의 상대 경과 시간 표시("2분 전", "3시간 전" 등).
 * features/dashboard·features/notification의 동명 유틸과 로직이 같지만, feature 간 직접 import가
 * 금지돼(React_코드_컨벤션.md §1) 이 feature 안에 별도로 둔다.
 * @param occurredAt - ISO 8601 날짜 문자열
 * @param now - 기준 시각 (테스트 용이성을 위해 주입, 기본값 현재 시각)
 */
export function formatElapsedTime(occurredAt: string, now: Date = new Date()): string {
  const diffMs = now.getTime() - new Date(occurredAt).getTime();

  if (diffMs < MINUTE_MS) return '방금 전';
  if (diffMs < HOUR_MS) return `${Math.floor(diffMs / MINUTE_MS)}분 전`;
  if (diffMs < DAY_MS) return `${Math.floor(diffMs / HOUR_MS)}시간 전`;
  return `${Math.floor(diffMs / DAY_MS)}일 전`;
}
