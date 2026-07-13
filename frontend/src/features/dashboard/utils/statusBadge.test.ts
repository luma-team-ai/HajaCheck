import { describe, it, expect } from 'vitest';
import { getInspectionStatusVariant } from './statusBadge';
import type { InspectionStatus } from '../types';

describe('getInspectionStatusVariant', () => {
  it('분석중은 blue를 반환한다', () => {
    expect(getInspectionStatusVariant('분석중')).toBe('blue');
  });

  it('검수대기는 orange를 반환한다', () => {
    expect(getInspectionStatusVariant('검수대기')).toBe('orange');
  });

  it('조치대기는 orange를 반환한다', () => {
    expect(getInspectionStatusVariant('조치대기')).toBe('orange');
  });

  it('완료는 green을 반환한다', () => {
    expect(getInspectionStatusVariant('완료')).toBe('green');
  });

  it('모든 InspectionStatus 값에 대해 매핑이 존재한다', () => {
    const statuses: InspectionStatus[] = ['분석중', '검수대기', '조치대기', '완료'];
    statuses.forEach((status) => {
      expect(getInspectionStatusVariant(status)).toBeTruthy();
    });
  });
});
