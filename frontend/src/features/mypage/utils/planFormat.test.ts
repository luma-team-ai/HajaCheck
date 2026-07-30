import { describe, expect, it } from 'vitest';
import {
  formatBillingDate,
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

  it('한도가 0이고 사용량이 0보다 커도(비정상 데이터) null을 반환한다', () => {
    expect(usagePercent(5, 0)).toBeNull();
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

describe('formatBillingDate', () => {
  it('ISO 날짜(YYYY-MM-DD)를 그대로 표기한다', () => {
    expect(formatBillingDate('2026-08-01')).toBe('2026-08-01');
  });

  it('시각이 포함된 ISO 문자열이 와도 날짜 부분만 표기한다', () => {
    expect(formatBillingDate('2026-08-01T09:00:00Z')).toBe('2026-08-01');
  });

  it('한 자릿수 월/일도 0으로 패딩한다', () => {
    expect(formatBillingDate('2026-01-05')).toBe('2026-01-05');
  });

  it('파싱할 수 없는 값은 원문을 그대로 반환한다', () => {
    expect(formatBillingDate('invalid-date')).toBe('invalid-date');
  });
});
