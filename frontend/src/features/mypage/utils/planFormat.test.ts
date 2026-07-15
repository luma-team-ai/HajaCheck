import { describe, expect, it } from 'vitest';
import {
  formatLimit,
  formatPriceMonthly,
  isUsageWarning,
  usagePercent,
} from './planFormat';

describe('formatLimit', () => {
  it('null(무제한)이면 "무제한"을 반환한다', () => {
    expect(formatLimit(null)).toBe('무제한');
  });

  it('숫자면 천단위 콤마로 표기한다', () => {
    expect(formatLimit(1000)).toBe('1,000');
  });
});

describe('usagePercent', () => {
  it('한도가 null(무제한)이면 null을 반환한다', () => {
    expect(usagePercent(786, null)).toBeNull();
  });

  it('한도가 0이면 null을 반환한다(0으로 나누기 방지)', () => {
    expect(usagePercent(0, 0)).toBeNull();
  });

  it('정상 비율을 반올림해 반환한다', () => {
    expect(usagePercent(786, 1000)).toBe(79);
  });

  it('사용량이 한도를 초과해도 100으로 clamp한다', () => {
    expect(usagePercent(150, 100)).toBe(100);
  });
});

describe('isUsageWarning', () => {
  it('80% 미만이면 경고가 아니다', () => {
    expect(isUsageWarning(79)).toBe(false);
  });

  it('80% 이상이면 경고다', () => {
    expect(isUsageWarning(80)).toBe(true);
  });

  it('null(무제한)이면 경고가 아니다', () => {
    expect(isUsageWarning(null)).toBe(false);
  });
});

describe('formatPriceMonthly', () => {
  it('원화 기호와 천단위 콤마, /월 접미사를 붙인다', () => {
    expect(formatPriceMonthly(99000)).toBe('₩99,000/월');
  });
});
