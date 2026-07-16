import { describe, it, expect } from 'vitest';
import { getInspectionStatusClass } from './statusBadge';
import type { InspectionStatus } from '../types';

describe('getInspectionStatusClass', () => {
  it('분석중은 blue 계열 클래스를 반환한다', () => {
    expect(getInspectionStatusClass('분석중')).toBe('bg-[#e6ecff] text-[#3452e0]');
  });

  it('검수대기는 orange 계열 클래스를 반환한다', () => {
    expect(getInspectionStatusClass('검수대기')).toBe('bg-[#fdf0d5] text-[#b5670a]');
  });

  it('조치대기는 orange 계열 클래스를 반환한다', () => {
    expect(getInspectionStatusClass('조치대기')).toBe('bg-[#fdf0d5] text-[#b5670a]');
  });

  it('완료는 green 계열 클래스를 반환한다', () => {
    expect(getInspectionStatusClass('완료')).toBe('bg-[#e3f5e6] text-[#16a34a]');
  });

  it('모든 InspectionStatus 값에 대해 매핑이 존재한다', () => {
    const statuses: InspectionStatus[] = ['분석중', '검수대기', '조치대기', '완료'];
    statuses.forEach((status) => {
      expect(getInspectionStatusClass(status)).toBeTruthy();
    });
  });
});
