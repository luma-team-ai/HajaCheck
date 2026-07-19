import { EMPTY_CELL } from '../constants';

// 사용자 관리 표의 날짜 셀 포맷 — Figma node-id 177-2017 표기 규칙.
// 가입일은 "2023.10.12", 최근 접속은 "방금 전 / 2시간 전 / 어제 / 5일 전 / 1주 전 / 1개월 전" 상대 표기.

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const WEEK = 7 * DAY;
const MONTH = 30 * DAY;
const YEAR = 365 * DAY;

/** ISO date(YYYY-MM-DD) → "YYYY.MM.DD". null/파싱 불가 시 "-" */
export function formatJoinedAt(isoDate: string | null): string {
  if (!isoDate) {
    return EMPTY_CELL;
  }
  const [year, month, day] = isoDate.split('-');
  if (!year || !month || !day) {
    return EMPTY_CELL;
  }
  return `${year}.${month}.${day}`;
}

/**
 * ISO datetime → 상대 시간 라벨. null이면 "-".
 * now를 주입 가능하게 둬 테스트가 시스템 시계에 의존하지 않게 한다.
 */
export function formatRelativeAccess(isoDateTime: string | null, now: number = Date.now()): string {
  if (!isoDateTime) {
    return EMPTY_CELL;
  }
  const timestamp = new Date(isoDateTime).getTime();
  if (Number.isNaN(timestamp)) {
    return EMPTY_CELL;
  }

  // 미래 시각(서버·클라이언트 시계 오차)은 "방금 전"으로 수렴시킨다 — "-3시간 전" 같은 음수 표기 방지
  const elapsed = Math.max(0, now - timestamp);

  if (elapsed < HOUR) {
    return '방금 전';
  }
  if (elapsed < DAY) {
    return `${Math.floor(elapsed / HOUR)}시간 전`;
  }
  if (elapsed < 2 * DAY) {
    return '어제';
  }
  if (elapsed < WEEK) {
    return `${Math.floor(elapsed / DAY)}일 전`;
  }
  if (elapsed < MONTH) {
    return `${Math.floor(elapsed / WEEK)}주 전`;
  }
  if (elapsed < YEAR) {
    return `${Math.floor(elapsed / MONTH)}개월 전`;
  }
  return `${Math.floor(elapsed / YEAR)}년 전`;
}
