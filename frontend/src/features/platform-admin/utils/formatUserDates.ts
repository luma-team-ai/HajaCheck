import { EMPTY_CELL } from '../constants';

// 사용자 관리 표의 날짜 셀 포맷 — Figma node-id 177-2017 표기 규칙에서, 가입일은 시각까지
// 확인 가능해야 한다는 요청으로 "YYYY-MM-DD HH:mm:ss"로 확장. 최근 접속은
// "방금 전 / 2시간 전 / 어제 / 5일 전 / 1주 전 / 1개월 전" 상대 표기.

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const WEEK = 7 * DAY;
const MONTH = 30 * DAY;
const YEAR = 365 * DAY;

// 백엔드 joinedAt(User.createdAt)은 LocalDateTime이라 오프셋 없는 "YYYY-MM-DDTHH:mm:ss[.SSS]"로
// 온다. new Date(...)로 파싱하면 날짜만 있는 문자열("YYYY-MM-DD")은 UTC로, 시각까지 있는 문자열은
// 로컬 시간대로 해석되는 JS 스펙 차이 때문에 같은 포맷터가 실행 환경(테스트 CI 등)의 시간대에 따라
// 다른 값을 낼 수 있다 — Date 객체를 거치지 않고 문자열을 직접 파싱해 이 문제를 피한다.
const DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}):(\d{2}))?/;

/** ISO date/datetime → "YYYY-MM-DD HH:mm:ss"(시각 없는 입력은 00:00:00). null/파싱 불가 시 "-" */
export function formatJoinedAt(isoDateTime: string | null): string {
  if (!isoDateTime) {
    return EMPTY_CELL;
  }
  const match = DATE_TIME_PATTERN.exec(isoDateTime);
  if (!match) {
    return EMPTY_CELL;
  }
  const [, year, month, day, hour = '00', minute = '00', second = '00'] = match;
  return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
}

/**
 * ISO datetime(Instant, UTC "...Z") → "YYYY-MM-DD HH:mm:ss"(로컬 시간대로 변환됨). null/파싱 불가 시 "-".
 * formatJoinedAt과 달리 new Date()를 거쳐 UTC→로컬 변환을 실제로 수행한다 — lastAccessAt(Instant,
 * UTC 직렬화)처럼 오프셋이 있는 문자열에 formatJoinedAt(오프셋 없는 LocalDateTime 전용, 정규식으로
 * 숫자만 추출)을 쓰면 UTC 시각을 로컬 시각인 것처럼 그대로 찍어버려 9시간(KST) 어긋난다.
 * 인쇄물(AdminUserPrintTable)처럼 상대 시간이 아니라 고정 시각이 필요한 곳에서 쓴다.
 */
export function formatAbsoluteAccess(isoDateTime: string | null): string {
  if (!isoDateTime) {
    return EMPTY_CELL;
  }
  const date = new Date(isoDateTime);
  if (Number.isNaN(date.getTime())) {
    return EMPTY_CELL;
  }
  const pad = (value: number) => String(value).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
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
