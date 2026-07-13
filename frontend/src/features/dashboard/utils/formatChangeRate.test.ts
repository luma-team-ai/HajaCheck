import { describe, it, expect } from 'vitest';
import { formatChangeRate } from './formatChangeRate';

describe('formatChangeRate', () => {
  it('양수는 상승 화살표와 함께 표시한다', () => {
    expect(formatChangeRate(8)).toBe('↗8%');
  });

  it('음수는 하강 화살표와 함께 절대값으로 표시한다', () => {
    expect(formatChangeRate(-4)).toBe('↘4%');
  });

  it('0은 화살표 없이 표시한다', () => {
    expect(formatChangeRate(0)).toBe('0%');
  });
});
