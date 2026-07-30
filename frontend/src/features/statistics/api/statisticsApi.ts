import { api } from '../../../shared/api/axios';
import type {
  DefectTypeDistributionItem,
  FacilitySummaryItem,
  FacilityTypeMonthlyHeatmapCell,
  GradeDistributionItem,
  MonthlyDefectTrendItem,
  StatisticsFilterParams,
  StatisticsKpiSummary,
} from '../types';

// dashboard/api/dashboardApi.ts와 동일 패턴 — 백엔드 /api/statistics/* 엔드포인트에
// period, facilityId 쿼리 파라미터를 정상 전달하도록 설계한다.
export const statisticsApi = {
  getSummary: (params?: StatisticsFilterParams) =>
    api.get<StatisticsKpiSummary>('/statistics/summary', { params }),
  getMonthlyTrend: (params?: StatisticsFilterParams) =>
    api.get<MonthlyDefectTrendItem[]>('/statistics/monthly-trend', { params }),
  getDefectTypeDistribution: (params?: StatisticsFilterParams) =>
    api.get<DefectTypeDistributionItem[]>('/statistics/defect-type-distribution', { params }),
  getGradeDistribution: (params?: StatisticsFilterParams) =>
    api.get<GradeDistributionItem[]>('/statistics/grade-distribution', { params }),
  getFacilityTypeHeatmap: (params?: StatisticsFilterParams) =>
    api.get<FacilityTypeMonthlyHeatmapCell[]>('/statistics/facility-type-heatmap', { params }),
  getFacilitySummary: (params?: StatisticsFilterParams) =>
    api.get<FacilitySummaryItem[]>('/statistics/facility-summary', { params }),
};
