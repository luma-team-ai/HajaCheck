import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
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
  // 내 점검 이력 / 보고서 (HAJA-366, #668) — BE 미구현(grep 0건), 경로는 기존 /me/* 규약을 따른
  // 추정 스펙이다. fetchWithFallback이 NETWORK_ERROR일 때만 mock으로 폴백한다.
  getInspectionsSummary: () => api.get<MyInspectionsSummary>('/me/inspections/summary'),
  getInspections: (params: { page: number; size: number }) =>
    api.get<PageResponse<InspectionHistoryRow>>('/me/inspections', { params }),
  getReports: () => api.get<MyReportCard[]>('/me/reports'),
};
