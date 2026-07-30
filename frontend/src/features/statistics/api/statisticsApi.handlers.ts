import { http, HttpResponse } from 'msw';
import { extractFacilityCategory } from '../../../shared/lib/facilityCategory';
import type { ApiResponse } from '../../../shared/api/types';
import {
  mockDefectTypeDistribution,
  mockFacilitySummary,
  mockFacilityTypeHeatmap,
  mockGradeDistribution,
  mockMonthlyDefectTrend,
  mockStatisticsKpiSummary,
} from '../mocks/statistics.mock';
import type {
  DefectTypeDistributionItem,
  FacilitySummaryItem,
  FacilityTypeMonthlyHeatmapCell,
  GradeDistributionItem,
  MonthlyDefectTrendItem,
  StatisticsKpiSummary,
} from '../types';

export const statisticsHandlers = [
  http.get('/api/statistics/summary', ({ request }) => {
    const url = new URL(request.url);
    const facilityId = url.searchParams.get('facilityId');
    const period = url.searchParams.get('period');

    let summary = { ...mockStatisticsKpiSummary };
    if (facilityId && facilityId !== 'all') {
      const facility = mockFacilitySummary.find((f) => String(f.facilityId) === facilityId);
      if (facility) {
        summary = {
          ...summary,
          totalDefects: facility.totalDefects,
          progressingDefects: Math.round(facility.totalDefects * 0.2),
        };
      }
    }

    if (period === '3m') {
      summary.totalDefects = Math.round(summary.totalDefects * 0.45);
    } else if (period === '1y') {
      summary.totalDefects = Math.round(summary.totalDefects * 1.6);
    }

    const body: ApiResponse<StatisticsKpiSummary> = { success: true, data: summary };
    return HttpResponse.json(body);
  }),

  http.get('/api/statistics/monthly-trend', ({ request }) => {
    const url = new URL(request.url);
    const period = url.searchParams.get('period');
    const facilityId = url.searchParams.get('facilityId');

    let trend = [...mockMonthlyDefectTrend];
    if (facilityId && facilityId !== 'all') {
      const facIdNum = Number(facilityId) || 1;
      trend = trend.map((item) => ({
        ...item,
        defectCount: Math.max(10, Math.round(item.defectCount * (0.2 + (facIdNum % 3) * 0.15))),
      }));
    }

    if (period === '3m') {
      trend = trend.slice(-3);
    } else if (period === '1y') {
      // mock 데이터는 6개월치만 있어 '1y'를 그대로 slice로 늘릴 수 없다 — 다른 카드처럼
      // 스케일 계수로 필터 반응성만 시연한다(실 API 연동 시 12개월 데이터로 교체).
      trend = trend.map((item) => ({ ...item, defectCount: Math.round(item.defectCount * 1.3) }));
    }

    const body: ApiResponse<MonthlyDefectTrendItem[]> = { success: true, data: trend };
    return HttpResponse.json(body);
  }),

  http.get('/api/statistics/defect-type-distribution', ({ request }) => {
    const url = new URL(request.url);
    const facilityId = url.searchParams.get('facilityId');

    let list = [...mockDefectTypeDistribution];
    if (facilityId && facilityId !== 'all') {
      const facIdNum = Number(facilityId) || 1;
      list = list.map((item) => ({
        ...item,
        count: Math.max(5, Math.round(item.count * (0.3 + (facIdNum % 2) * 0.3))),
      }));
    }

    const body: ApiResponse<DefectTypeDistributionItem[]> = {
      success: true,
      data: list,
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/statistics/grade-distribution', ({ request }) => {
    const url = new URL(request.url);
    const facilityId = url.searchParams.get('facilityId');

    let gradeDist = [...mockGradeDistribution];
    if (facilityId && facilityId !== 'all') {
      if (facilityId === '1') {
        gradeDist = [
          { grade: 'A', percent: 45 },
          { grade: 'B', percent: 30 },
          { grade: 'C', percent: 15 },
          { grade: 'D', percent: 7 },
          { grade: 'E', percent: 3 },
        ];
      } else if (facilityId === '2') {
        gradeDist = [
          { grade: 'A', percent: 20 },
          { grade: 'B', percent: 25 },
          { grade: 'C', percent: 35 },
          { grade: 'D', percent: 15 },
          { grade: 'E', percent: 5 },
        ];
      }
    }

    const body: ApiResponse<GradeDistributionItem[]> = { success: true, data: gradeDist };
    return HttpResponse.json(body);
  }),

  http.get('/api/statistics/facility-type-heatmap', ({ request }) => {
    const url = new URL(request.url);
    const period = url.searchParams.get('period');
    const facilityId = url.searchParams.get('facilityId');

    let heatmap = [...mockFacilityTypeHeatmap];

    if (period === '3m') {
      const months = [...new Set(heatmap.map((cell) => cell.month))].sort().slice(-3);
      heatmap = heatmap.filter((cell) => months.includes(cell.month));
    }

    if (facilityId && facilityId !== 'all') {
      const facility = mockFacilitySummary.find((f) => String(f.facilityId) === facilityId);
      const category = extractFacilityCategory(facility?.facilityType);
      if (category) {
        heatmap = heatmap.filter((cell) => cell.facilityTypeCategory === category);
      }
    }

    const body: ApiResponse<FacilityTypeMonthlyHeatmapCell[]> = {
      success: true,
      data: heatmap,
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/statistics/facility-summary', ({ request }) => {
    const url = new URL(request.url);
    const facilityId = url.searchParams.get('facilityId');

    let list = [...mockFacilitySummary];
    if (facilityId && facilityId !== 'all') {
      list = list.filter((item) => String(item.facilityId) === facilityId);
    }

    const body: ApiResponse<FacilitySummaryItem[]> = { success: true, data: list };
    return HttpResponse.json(body);
  }),
];
