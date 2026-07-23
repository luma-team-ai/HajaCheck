import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type {
  InspectionHistoryRow,
  MyInspectionsSummary,
  MyPlan,
  MyReportCard,
  SeatsInfo,
  UpgradeInquiryResult,
} from '../types';

export const mypageApi = {
  getPlan: () => api.get<MyPlan>('/me/plan'),
  getSeats: () => api.get<SeatsInfo>('/me/seats'),
  requestUpgrade: () => api.post<UpgradeInquiryResult>('/me/plan/upgrade-inquiry'),
  // 내 점검 이력 / 보고서 (HAJA-366, #668) — BE 미구현(grep 0건), 경로는 기존 /me/* 규약을 따른
  // 추정 스펙이다. fetchWithFallback이 NETWORK_ERROR일 때만 mock으로 폴백한다.
  getInspectionsSummary: () => api.get<MyInspectionsSummary>('/me/inspections/summary'),
  getInspections: (params: { page: number; size: number }) =>
    api.get<PageResponse<InspectionHistoryRow>>('/me/inspections', { params }),
  getReports: () => api.get<MyReportCard[]>('/me/reports'),
};
