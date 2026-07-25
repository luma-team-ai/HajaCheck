import { http, HttpResponse } from 'msw';
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

    if (period === '1m') {
      summary.totalDefects = Math.round(summary.totalDefects * 0.15);
    } else if (period === '3m') {
      summary.totalDefects = Math.round(summary.totalDefects * 0.45);
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

    if (period === '1m') {
      trend = trend.slice(-1);
    } else if (period === '3m') {
      trend = trend.slice(-3);
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

  http.get('/api/statistics/facility-type-heatmap', () => {
    const body: ApiResponse<FacilityTypeMonthlyHeatmapCell[]> = {
      success: true,
      data: mockFacilityTypeHeatmap,
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
