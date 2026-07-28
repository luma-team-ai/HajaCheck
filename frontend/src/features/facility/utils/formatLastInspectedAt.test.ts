import { describe, expect, it } from 'vitest';
import { formatLastInspectedAt } from './formatLastInspectedAt';

describe('formatLastInspectedAt', () => {
  it('ISO date를 MM.dd로 변환한다', () => {
    expect(formatLastInspectedAt('2026-06-21')).toBe('06.21');
  });

  it('null이면 null을 반환한다', () => {
    expect(formatLastInspectedAt(null)).toBeNull();
  });
});
