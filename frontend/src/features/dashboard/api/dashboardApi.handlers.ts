import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import {
  mockAiBriefing,
  mockDashboardSummary,
  mockGradeDistribution,
  mockPendingPriority,
  mockRecentInspections,
} from '../mocks/dashboard.mock';
import type {
  AiBriefing,
  DashboardSummary,
  GradeDistributionItem,
  PendingPriorityItem,
  RecentInspectionItem,
} from '../types';

export const dashboardHandlers = [
  http.get('/api/dashboard/summary', () => {
    const body: ApiResponse<DashboardSummary> = { success: true, data: mockDashboardSummary };
    return HttpResponse.json(body);
  }),

  http.get('/api/dashboard/grade-distribution', () => {
    const body: ApiResponse<GradeDistributionItem[]> = {
      success: true,
      data: mockGradeDistribution,
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/dashboard/pending-priority', () => {
    const body: ApiResponse<PendingPriorityItem[]> = { success: true, data: mockPendingPriority };
    return HttpResponse.json(body);
  }),

  http.get('/api/dashboard/recent-inspections', () => {
    const body: ApiResponse<RecentInspectionItem[]> = {
      success: true,
      data: mockRecentInspections,
    };
    return HttpResponse.json(body);
  }),

  // ai-server AIResponse({success,data,usage,error}) — 스프링 인증 프록시 /api/ai/* 경유(vite.config.ts /api 프록시)
  http.post('/api/ai/briefing', () => {
    const body: { success: boolean; data: AiBriefing; usage: { tokens: number } } = {
      success: true,
      data: mockAiBriefing,
      usage: { tokens: 0 },
    };
    return HttpResponse.json(body);
  }),
];
