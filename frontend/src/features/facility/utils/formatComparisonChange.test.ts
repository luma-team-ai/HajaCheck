import { describe, expect, it } from 'vitest';
import { formatComparisonChange, getComparisonChangeColorClass } from './formatComparisonChange';

describe('formatComparisonChange', () => {
  it('양수는 ↑ 화살표와 절댓값을 반환한다', () => {
    expect(formatComparisonChange(8)).toBe('↑8');
  });

  it('음수는 ↓ 화살표와 절댓값을 반환한다', () => {
    expect(formatComparisonChange(-5)).toBe('↓5');
  });

  it('0은 화살표 없이 "0"을 반환한다', () => {
    expect(formatComparisonChange(0)).toBe('0');
  });
});

describe('getComparisonChangeColorClass', () => {
  it('양수는 빨강 계열 클래스를 반환한다', () => {
    expect(getComparisonChangeColorClass(3)).toBe('text-[#dc2626]');
  });

  it('음수는 초록 계열 클래스를 반환한다(개선/감소 방향이어도 부호로만 판단)', () => {
    expect(getComparisonChangeColorClass(-5)).toBe('text-[#16a34a]');
  });

  it('0은 중립 클래스를 반환한다', () => {
    expect(getComparisonChangeColorClass(0)).toBe('text-text-muted');
  });
});