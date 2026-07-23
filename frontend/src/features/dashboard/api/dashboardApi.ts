import { api } from '../../../shared/api/axios';
import { aiClient } from '../../../shared/api/aiClient';
import type {
  AiBriefing,
  DashboardSummary,
  GradeDistributionItem,
  PendingPriorityItem,
  RecentInspectionItem,
  UpcomingInspectionItem,
} from '../types';

export const dashboardApi = {
  getSummary: () => api.get<DashboardSummary>('/dashboard/summary'),
  getGradeDistribution: () => api.get<GradeDistributionItem[]>('/dashboard/grade-distribution'),
  getPendingPriority: () => api.get<PendingPriorityItem[]>('/dashboard/pending-priority'),
  getRecentInspections: () => api.get<RecentInspectionItem[]>('/dashboard/recent-inspections'),
  // dev-03-02, #469 — 다음 점검일 도래(days/limit 쿼리, 기본값은 BE가 관리)
  getUpcomingInspections: () =>
    api.get<UpcomingInspectionItem[]>('/dashboard/upcoming-inspections'),
  // ai-server #108 — 대시보드 현황 기반 AI 주간 브리핑
  getBriefing: () => aiClient.post<AiBriefing>('/briefing'),
};
