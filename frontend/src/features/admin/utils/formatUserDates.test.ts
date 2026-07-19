import { describe, expect, it } from 'vitest';
import { formatJoinedAt, formatRelativeAccess } from './formatUserDates';

// now를 주입해 시스템 시계에 의존하지 않게 한다 — 기준 시각 2026-07-19T12:00:00Z
const NOW = new Date('2026-07-19T12:00:00.000Z').getTime();

function isoBefore(ms: number): string {
  return new Date(NOW - ms).toISOString();
}

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe('formatJoinedAt', () => {
  it('ISO date를 점 구분 표기로 바꾼다', () => {
    expect(formatJoinedAt('2023-10-12')).toBe('2023.10.12');
  });

  it('가입일이 없으면(미가입 초대 사용자) "-"를 반환한다', () => {
    expect(formatJoinedAt(null)).toBe('-');
  });

  it('형식이 깨진 값은 "-"로 떨어뜨린다', () => {
    expect(formatJoinedAt('2023-10')).toBe('-');
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
