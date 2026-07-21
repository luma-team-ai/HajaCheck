const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

// dashboard/utils/formatElapsedTime.ts와 로직이 동일하다 — feature 간 직접 import가 금지라
// (React_코드_컨벤션.md §1) 소규모 순수 함수라 복제했다. 두 곳 이상에서 더 쓰이게 되면 shared/utils로
// 승격 검토(Frontend 리드 협의).
/**
 * 알림 발생 시각을 상대 경과 시간 문자열로 변환합니다. ("방금 전", "12분 전", "3시간 전", "1일 전")
 * @param createdAt - ISO 8601 날짜 문자열(BE LocalDateTime 직렬화)
 * @param now - 기준 시각(테스트 용이성을 위해 주입, 기본값 현재 시각)
 */
export function formatElapsedTime(createdAt: string, now: Date = new Date()): string {
  const diffMs = now.getTime() - new Date(createdAt).getTime();

  if (diffMs < MINUTE_MS) return '방금 전';
  if (diffMs < HOUR_MS) return `${Math.floor(diffMs / MINUTE_MS)}분 전`;
  if (diffMs < DAY_MS) return `${Math.floor(diffMs / HOUR_MS)}시간 전`;
  return `${Math.floor(diffMs / DAY_MS)}일 전`;
}
