import { describe, expect, it } from 'vitest';
import { computeNextInspectionDueAt } from './computeNextInspectionDueAt';

function addMonthsIso(months: number): string {
  const date = new Date();
  date.setMonth(date.getMonth() + months);
  return date.toISOString().slice(0, 10);
}

describe('computeNextInspectionDueAt', () => {
  it('점검주기가 6개월이면 오늘로부터 6개월 뒤 날짜(YYYY-MM-DD)를 반환한다', () => {
    expect(computeNextInspectionDueAt(6)).toBe(addMonthsIso(6));
  });

  it('점검주기가 1개월이면 오늘로부터 1개월 뒤 날짜를 반환한다', () => {
    expect(computeNextInspectionDueAt(1)).toBe(addMonthsIso(1));
  });

  it('점검주기가 0이면 null을 반환한다', () => {
    expect(computeNextInspectionDueAt(0)).toBeNull();
  });

  it('점검주기가 음수면 null을 반환한다', () => {
    expect(computeNextInspectionDueAt(-3)).toBeNull();
  });

  it('점검주기가 null이면 null을 반환한다', () => {
    expect(computeNextInspectionDueAt(null)).toBeNull();
  });

  it('점검주기가 undefined이면 null을 반환한다', () => {
    expect(computeNextInspectionDueAt(undefined)).toBeNull();
  });
});
