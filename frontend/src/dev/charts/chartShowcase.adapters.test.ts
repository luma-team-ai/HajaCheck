import { describe, expect, it } from 'vitest';
import type { RecentInspectionItem } from '../../features/dashboard/types';
import {
  toGradeDistributionChartData,
  toInspectionTrendChartData,
  toStatusDistributionChartData,
} from './chartShowcase.adapters';
import { showcaseGradeDistribution, showcaseRecentInspections } from './chartShowcase.mock';

describe('chartShowcase adapters', () => {
  it('최근 점검 DTO를 날짜순 추이 데이터로 변환하고 0건을 유지한다', () => {
    const reversed = [...showcaseRecentInspections].reverse();

    const result = toInspectionTrendChartData(reversed);

    expect(result[0]).toEqual({ inspectedAt: '2026-07-01', defectCount: 3 });
    expect(result.some((item) => item.defectCount === 0)).toBe(true);
  });

  it('등급 분포 DTO에서 차트에 필요한 필드만 복사한다', () => {
    expect(toGradeDistributionChartData(showcaseGradeDistribution)).toEqual(showcaseGradeDistribution);
  });

  it('최근 점검 DTO를 정의된 상태 순서의 건수로 집계한다', () => {
    const result = toStatusDistributionChartData(showcaseRecentInspections);

    expect(result).toEqual([
      { status: '분석중', count: 1 },
      { status: '검수대기', count: 1 },
      { status: '조치대기', count: 1 },
      { status: '완료', count: 3 },
    ]);
  });

  it('없는 상태는 파이 조각에서 제외한다', () => {
    const completedOnly: RecentInspectionItem[] = [showcaseRecentInspections[0]];

    expect(toStatusDistributionChartData(completedOnly)).toEqual([{ status: '완료', count: 1 }]);
  });
});
