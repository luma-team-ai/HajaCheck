import { describe, expect, it } from 'vitest';
import { getMonthsForPeriod } from './getMonthsForPeriod';

describe('getMonthsForPeriod', () => {
  it('3m 필터 시 데이터의 최근 월 기준으로 과거 3개월치 월 리스트를 생성한다', () => {
    const months = getMonthsForPeriod('3m', ['2026-07']);
    expect(months).toEqual(['2026-05', '2026-06', '2026-07']);
  });

  it('6m 필터 시 데이터의 최근 월 기준으로 과거 6개월치 월 리스트를 생성한다', () => {
    const months = getMonthsForPeriod('6m', ['2026-07']);
    expect(months).toEqual(['2026-02', '2026-03', '2026-04', '2026-05', '2026-06', '2026-07']);
  });

  it('1y 필터 시 데이터의 최근 월 기준으로 과거 12개월치 월 리스트를 생성한다', () => {
    const months = getMonthsForPeriod('1y', ['2026-07']);
    expect(months).toHaveLength(12);
    expect(months[0]).toBe('2025-08');
    expect(months[11]).toBe('2026-07');
  });

  it('데이터에 이전 월이 누락되어도 필터 개수(N개)만큼 보장하여 반환한다', () => {
    // 2026-05, 2026-06 데이터가 없이 2026-07만 존재할 때
    const months = getMonthsForPeriod('3m', ['2026-07']);
    expect(months).toHaveLength(3);
    expect(months).toContain('2026-05');
    expect(months).toContain('2026-06');
    expect(months).toContain('2026-07');
  });
});
