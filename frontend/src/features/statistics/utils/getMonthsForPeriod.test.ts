import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getMonthsForPeriod } from './getMonthsForPeriod';

describe('getMonthsForPeriod', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-15T00:00:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('3m 필터 시 오늘(2026-08) 기준 과거 3개월치 월 리스트를 생성한다', () => {
    const months = getMonthsForPeriod('3m', ['2026-07']);
    expect(months).toEqual(['2026-06', '2026-07', '2026-08']);
  });

  it('6m 필터 시 오늘(2026-08) 기준 과거 6개월치 월 리스트를 생성한다', () => {
    const months = getMonthsForPeriod('6m', ['2026-07']);
    expect(months).toEqual(['2026-03', '2026-04', '2026-05', '2026-06', '2026-07', '2026-08']);
  });

  it('1y 필터 시 오늘(2026-08) 기준 과거 12개월치 월 리스트를 생성한다', () => {
    const months = getMonthsForPeriod('1y', ['2026-07']);
    expect(months).toHaveLength(12);
    expect(months[0]).toBe('2025-09');
    expect(months[11]).toBe('2026-08');
  });

  // 회귀 가드(#1696): 그 달(당월) 점검이 0건이라 서버 응답(dataMonths)에 당월이 없어도
  // 축의 끝은 여전히 당월이어야 한다 — 이전에는 dataMonths의 마지막 월을 끝점으로 삼아
  // 당월 열이 통째로 사라졌다.
  it('데이터가 지난달까지만 있어도 이번 달 열이 포함된다', () => {
    const months = getMonthsForPeriod('3m', ['2026-06', '2026-07']);
    expect(months).toContain('2026-08');
    expect(months).toEqual(['2026-06', '2026-07', '2026-08']);
  });

  it('데이터가 전무해도 오늘 기준 N개월을 생성한다', () => {
    const months = getMonthsForPeriod('3m', []);
    expect(months).toEqual(['2026-06', '2026-07', '2026-08']);
  });

  it('기간 밖 과거 월이 dataMonths에 있으면 포괄되어 반환된다', () => {
    const months = getMonthsForPeriod('3m', ['2026-01']);
    expect(months).toEqual(['2026-01', '2026-06', '2026-07', '2026-08']);
  });

  it('연말 경계를 넘어 월 감산이 정확히 이뤄진다 (오늘 2026-01, 6m)', () => {
    vi.setSystemTime(new Date('2026-01-10T00:00:00'));
    const months = getMonthsForPeriod('6m', []);
    expect(months).toEqual(['2025-08', '2025-09', '2025-10', '2025-11', '2025-12', '2026-01']);
  });
});
