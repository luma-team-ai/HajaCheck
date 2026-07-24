import { describe, expect, it } from 'vitest';
import { formatJoinedDate } from './profileFormat';

describe('formatJoinedDate', () => {
  it('시각이 포함된 ISO 문자열(LocalDateTime)에서 날짜 부분만 "YYYY.MM.DD"로 표기한다', () => {
    expect(formatJoinedDate('2026-07-24T14:30:00')).toBe('2026.07.24');
  });

  it('날짜만 있는 ISO 문자열도 동일하게 표기한다', () => {
    expect(formatJoinedDate('2026-07-24')).toBe('2026.07.24');
  });

  it('한 자릿수 월/일도 0으로 패딩된 원문을 그대로 조합한다', () => {
    expect(formatJoinedDate('2026-01-05T00:00:00')).toBe('2026.01.05');
  });

  it('파싱할 수 없는 값은 원문을 그대로 반환한다', () => {
    expect(formatJoinedDate('invalid-date')).toBe('invalid-date');
  });
});
