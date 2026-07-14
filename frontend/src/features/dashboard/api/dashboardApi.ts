import { api } from '../../../shared/api/axios';
import { aiClient } from '../../../shared/api/aiClient';
import type {
  AiBriefing,
  DashboardSummary,
  GradeDistributionItem,
  PendingPriorityItem,
  RecentInspectionItem,
} from '../types';

export const dashboardApi = {
  getSummary: () => api.get<DashboardSummary>('/dashboard/summary'),
  getGradeDistribution: () => api.get<GradeDistributionItem[]>('/dashboard/grade-distribution'),
  getPendingPriority: () => api.get<PendingPriorityItem[]>('/dashboard/pending-priority'),
  getRecentInspections: () => api.get<RecentInspectionItem[]>('/dashboard/recent-inspections'),
  // ai-server #108 — 대시보드 현황 기반 AI 주간 브리핑
  getBriefing: () => aiClient.post<AiBriefing>('/briefing'),
};
