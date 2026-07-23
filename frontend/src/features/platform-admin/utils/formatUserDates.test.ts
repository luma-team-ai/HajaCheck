import { describe, expect, it } from 'vitest';
import { formatAbsoluteAccess, formatJoinedAt, formatRelativeAccess } from './formatUserDates';

// now를 주입해 시스템 시계에 의존하지 않게 한다 — 기준 시각 2026-07-19T12:00:00Z
const NOW = new Date('2026-07-19T12:00:00.000Z').getTime();

function isoBefore(ms: number): string {
  return new Date(NOW - ms).toISOString();
}

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe('formatJoinedAt', () => {
  it('시각이 없는 ISO date는 00:00:00으로 채운다', () => {
    expect(formatJoinedAt('2023-10-12')).toBe('2023-10-12 00:00:00');
  });

  it('백엔드 LocalDateTime(오프셋 없는 datetime)을 초 단위까지 표시한다', () => {
    expect(formatJoinedAt('2026-07-19T09:03:07')).toBe('2026-07-19 09:03:07');
  });

  it('가입일이 없으면(미가입 초대 사용자) "-"를 반환한다', () => {
    expect(formatJoinedAt(null)).toBe('-');
  });

  it('형식이 깨진 값은 "-"로 떨어뜨린다', () => {
    expect(formatJoinedAt('2023-10')).toBe('-');
  });

  // #404 — created_at이 date-only가 아니라 오프셋 있는 ISO datetime(Instant 직렬화, 예: "...Z"/
  // "+09:00")으로 와도 split('-') 방식처럼 day 조각에 시각이 섞여 들어가지 않아야 한다.
  // DATE_TIME_PATTERN이 시작부터 날짜·시각만 추출하고 뒤에 남는 오프셋 표기는 무시하므로 통과한다.
  it('오프셋이 붙은 ISO datetime(Z/+09:00)도 날짜와 시각만 추출한다', () => {
    expect(formatJoinedAt('2023-10-12T09:00:00Z')).toBe('2023-10-12 09:00:00');
    expect(formatJoinedAt('2023-10-12T09:00:00+09:00')).toBe('2023-10-12 09:00:00');
  });
});

describe('formatAbsoluteAccess', () => {
  it('UTC Instant를 (실행 환경의) 로컬 시각으로 변환해 "YYYY-MM-DD HH:mm:ss"로 표시한다', () => {
    const iso = '2026-07-19T05:03:07.000Z';
    const date = new Date(iso);
    const pad = (value: number) => String(value).padStart(2, '0');
    const expected =
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;

    expect(formatAbsoluteAccess(iso)).toBe(expected);
  });

  it('접속 이력이 없으면 "-"', () => {
    expect(formatAbsoluteAccess(null)).toBe('-');
  });

  it('형식이 깨진 값은 "-"로 떨어뜨린다', () => {
    expect(formatAbsoluteAccess('not-a-date')).toBe('-');
  });
});

describe('formatRelativeAccess', () => {
  it('1시간 미만은 "방금 전"', () => {
    expect(formatRelativeAccess(isoBefore(30 * MINUTE), NOW)).toBe('방금 전');
  });

  it('당일 내는 시간 단위', () => {
    expect(formatRelativeAccess(isoBefore(2 * HOUR), NOW)).toBe('2시간 전');
  });

  it('하루~이틀 사이는 "어제"', () => {
    expect(formatRelativeAccess(isoBefore(30 * HOUR), NOW)).toBe('어제');
  });

  it('일주일 미만은 일 단위', () => {
    expect(formatRelativeAccess(isoBefore(5 * DAY), NOW)).toBe('5일 전');
  });

  it('한 달 미만은 주 단위', () => {
    expect(formatRelativeAccess(isoBefore(8 * DAY), NOW)).toBe('1주 전');
  });

  it('한 달 이상은 개월 단위', () => {
    expect(formatRelativeAccess(isoBefore(31 * DAY), NOW)).toBe('1개월 전');
  });

  it('접속 이력이 없으면 "-"', () => {
    expect(formatRelativeAccess(null, NOW)).toBe('-');
  });

  it('서버·클라이언트 시계 오차로 미래 시각이 와도 음수 라벨을 만들지 않는다', () => {
    expect(formatRelativeAccess(isoBefore(-3 * HOUR), NOW)).toBe('방금 전');
  });
});
