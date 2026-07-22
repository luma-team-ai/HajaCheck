import { describe, it, expect } from 'vitest';
import { deriveUpcomingInspectionStatusKind } from './upcomingInspectionStatus';

describe('deriveUpcomingInspectionStatusKind', () => {
  it('음수(초과)는 overdue를 반환한다', () => {
    expect(deriveUpcomingInspectionStatusKind(-1)).toBe('overdue');
  });

  it('7일 이하는 upcoming을 반환한다', () => {
    expect(deriveUpcomingInspectionStatusKind(0)).toBe('upcoming');
    expect(deriveUpcomingInspectionStatusKind(7)).toBe('upcoming');
  });

  it('8~60일은 grace를 반환한다', () => {
    expect(deriveUpcomingInspectionStatusKind(8)).toBe('grace');
    expect(deriveUpcomingInspectionStatusKind(60)).toBe('grace');
  });

  it('60일 초과는 safe를 반환한다', () => {
    expect(deriveUpcomingInspectionStatusKind(61)).toBe('safe');
  });
});
