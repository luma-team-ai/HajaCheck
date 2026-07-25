import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type { PeriodFilterValue } from '../components/PeriodFilterSelect';
import type {
  InspectionHistoryRow,
  MyInspectionsSummary,
  MyPlan,
  MyReportCard,
  PlanName,
  SeatsInfo,
} from '../types';

export const mypageApi = {
  getPlan: () => api.get<MyPlan>('/me/plan'),
  getSeats: () => api.get<SeatsInfo>('/me/seats'),
  // 모의 결제(PG 미연동) — 기존 업그레이드 문의(POST /me/plan/upgrade-inquiry)를 대체한다(#712 Figma
  // 리디자인, BE #711/PR#714에서 확정된 계약). STANDARD/ENTERPRISE만 대상, 응답은 갱신된 MyPlan.
  checkout: (planName: PlanName) => api.post<MyPlan>('/me/plan/checkout', { planName }),
  // 내 점검 이력 / 보고서 (HAJA-366/#668, BE 연동 #844/HAJA-442) — GET /api/me/inspections/summary,
  // /api/me/inspections, /api/me/reports 3종. period는 PeriodFilterSelect 값(1M/3M/6M/1Y/ALL)을
  // 쿼리 파라미터로 그대로 전달한다.
  getInspectionsSummary: () => api.get<MyInspectionsSummary>('/me/inspections/summary'),
  getInspections: (params: { page: number; size: number; period: PeriodFilterValue }) =>
    api.get<PageResponse<InspectionHistoryRow>>('/me/inspections', { params }),
  getReports: (period: PeriodFilterValue) =>
    api.get<MyReportCard[]>('/me/reports', { params: { period } }),
};
