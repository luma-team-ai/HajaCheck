// @vitest-environment jsdom
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import type {
  DefectTypeDistributionItem,
  FacilitySummaryItem,
  FacilityTypeMonthlyHeatmapCell,
  GradeDistributionItem,
  MonthlyDefectTrendItem,
  StatisticsKpiSummary,
} from '../types';
import { statisticsHandlers } from './statisticsApi.handlers';

const server = setupServer(...statisticsHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('통계 Mock handler — 기간 필터(period) 반응', () => {
  it('period=3m 요약이 6m(default)보다 totalDefects가 45%로 감소한다', async () => {
    const res = await fetch('/api/statistics/summary?period=3m');
    const body = (await res.json()) as ApiResponse<StatisticsKpiSummary>;
    expect(body.success).toBe(true);
    expect(body.data?.totalDefects).toBe(Math.round(1842 * 0.45));
  });

  it('period=1y 요약이 6m(default)보다 totalDefects가 160%로 증가한다', async () => {
    const res = await fetch('/api/statistics/summary?period=1y');
    const body = (await res.json()) as ApiResponse<StatisticsKpiSummary>;
    expect(body.data?.totalDefects).toBe(Math.round(1842 * 1.6));
  });

  it('period=3m monthly-trend는 최근 3개월만 반환한다', async () => {
    const res = await fetch('/api/statistics/monthly-trend?period=3m');
    const body = (await res.json()) as ApiResponse<MonthlyDefectTrendItem[]>;
    expect(body.data).toHaveLength(3);
    expect(body.data?.[0].month).toBe('2026-04');
  });

  it('period=1y monthly-trend는 defectCount가 1.3배로 스케일된다', async () => {
    const res = await fetch('/api/statistics/monthly-trend?period=1y');
    const body = (await res.json()) as ApiResponse<MonthlyDefectTrendItem[]>;
    // mock 데이터는 6개월치라 slice가 아닌 스케일링 — 첫 달 60 → 78
    expect(body.data?.[0].defectCount).toBe(Math.round(60 * 1.3));
  });

  it('period=3m heatmap은 최근 3개월치만 반환한다', async () => {
    const res = await fetch('/api/statistics/facility-type-heatmap?period=3m');
    const body = (await res.json()) as ApiResponse<FacilityTypeMonthlyHeatmapCell[]>;
    const months = [...new Set(body.data?.map((cell) => cell.month) ?? [])].sort();
    expect(months).toEqual(['2026-05', '2026-06', '2026-07']);
  });
});

describe('통계 Mock handler — 시설물 선택(facilityId) 필터 반응', () => {
  it('facilityId=1로 summary를 조회하면 해당 시설물의 totalDefects(18)가 반영된다', async () => {
    const res = await fetch('/api/statistics/summary?facilityId=1');
    const body = (await res.json()) as ApiResponse<StatisticsKpiSummary>;
    expect(body.data?.totalDefects).toBe(18);
    expect(body.data?.progressingDefects).toBe(Math.round(18 * 0.2));
  });

  it('facilityId=1로 grade-distribution을 조회하면 시설물 특화 분포(A 45%)가 반환된다', async () => {
    const res = await fetch('/api/statistics/grade-distribution?facilityId=1');
    const body = (await res.json()) as ApiResponse<GradeDistributionItem[]>;
    const gradeA = body.data?.find((g) => g.grade === 'A');
    expect(gradeA?.percent).toBe(45);
  });

  it('facilityId=2로 grade-distribution을 조회하면 다른 분포(A 20%)가 반환된다', async () => {
    const res = await fetch('/api/statistics/grade-distribution?facilityId=2');
    const body = (await res.json()) as ApiResponse<GradeDistributionItem[]>;
    const gradeA = body.data?.find((g) => g.grade === 'A');
    expect(gradeA?.percent).toBe(20);
  });

  it('facilityId=1로 facility-summary를 조회하면 해당 시설물 1건만 반환된다', async () => {
    const res = await fetch('/api/statistics/facility-summary?facilityId=1');
    const body = (await res.json()) as ApiResponse<FacilitySummaryItem[]>;
    expect(body.data).toHaveLength(1);
    expect(body.data?.[0].facilityName).toBe('여의도 파크센터');
  });

  it('facilityId=all이면 facility-summary 전체 목록을 반환한다', async () => {
    const res = await fetch('/api/statistics/facility-summary?facilityId=all');
    const body = (await res.json()) as ApiResponse<FacilitySummaryItem[]>;
    expect(body.data).toHaveLength(5);
  });
});

describe('통계 Mock handler — 복합 시설물 유형 카테고리 파싱 일관성', () => {
  it('facilityId=1(건물-정기-4개월)로 heatmap을 조회하면 건물 카테고리만 반환된다', async () => {
    const res = await fetch('/api/statistics/facility-type-heatmap?facilityId=1');
    const body = (await res.json()) as ApiResponse<FacilityTypeMonthlyHeatmapCell[]>;
    const categories = [...new Set(body.data?.map((cell) => cell.facilityTypeCategory) ?? [])];
    expect(categories).toEqual(['건물']);
  });

  it('facilityId=3(교량-정밀-12개월)로 heatmap을 조회하면 교량 카테고리만 반환된다', async () => {
    const res = await fetch('/api/statistics/facility-type-heatmap?facilityId=3');
    const body = (await res.json()) as ApiResponse<FacilityTypeMonthlyHeatmapCell[]>;
    const categories = [...new Set(body.data?.map((cell) => cell.facilityTypeCategory) ?? [])];
    expect(categories).toEqual(['교량']);
  });
});

describe('통계 Mock handler — 복합 조건(기간+시설물) 동시 적용', () => {
  it('facilityId=1 & period=3m heatmap은 건물이면서 최근 3개월만 반환한다', async () => {
    const res = await fetch('/api/statistics/facility-type-heatmap?facilityId=1&period=3m');
    const body = (await res.json()) as ApiResponse<FacilityTypeMonthlyHeatmapCell[]>;
    const months = [...new Set(body.data?.map((cell) => cell.month) ?? [])].sort();
    expect(body.data?.every((cell) => cell.facilityTypeCategory === '건물')).toBe(true);
    expect(months).toEqual(['2026-05', '2026-06', '2026-07']);
  });
});

describe('통계 Mock handler — 기본값(파라미터 없음)', () => {
  it('summary에 파라미터가 없으면 기본 mock값(totalDefects=1842)을 반환한다', async () => {
    const res = await fetch('/api/statistics/summary');
    const body = (await res.json()) as ApiResponse<StatisticsKpiSummary>;
    expect(body.data?.totalDefects).toBe(1842);
    expect(body.data?.progressingDefects).toBe(38);
  });

  it('defect-type-distribution에 파라미터가 없으면 기본 3종 분포를 반환한다', async () => {
    const res = await fetch('/api/statistics/defect-type-distribution');
    const body = (await res.json()) as ApiResponse<DefectTypeDistributionItem[]>;
    expect(body.data).toHaveLength(3);
    expect(body.data?.map((d) => d.type).sort()).toEqual(['균열', '박리·박락', '철근 노출']);
  });
});
