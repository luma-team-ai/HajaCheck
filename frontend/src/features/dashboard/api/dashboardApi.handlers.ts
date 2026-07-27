import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import {
  mockAiBriefing,
  mockDashboardSummary,
  mockGradeDistribution,
  mockPendingPriority,
  mockRecentInspectionFacilityTypeById,
  mockRecentInspections,
  mockRecentInspectionsFull,
  mockUpcomingInspections,
} from '../mocks/dashboard.mock';
import type {
  AiBriefing,
  DashboardSummary,
  GradeDistributionItem,
  PendingPriorityItem,
  RecentInspectionItem,
  UpcomingInspectionItem,
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

  // "최근 점검 전체보기"(신규) — 기존 /recent-inspections(위젯) 핸들러와 별개, contract.md
  // §"대시보드 '최근 점검 전체보기' 페이지네이션+검색 API"의 필터·페이지네이션 규약을 목에서도 재현.
  http.get('/api/dashboard/recent-inspections/search', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '10');
    const status = url.searchParams.get('status');
    const facilityType = url.searchParams.get('facilityType');
    const query = url.searchParams.get('query')?.trim().toLowerCase();

    let filtered = mockRecentInspectionsFull;
    if (status) {
      filtered = filtered.filter((item) => item.status === status);
    }
    if (facilityType) {
      // 백엔드와 동일하게 접두(prefix) 매칭 — "건물" 선택 시 "건물-긴급-1개월" 컴파운드값도 포함.
      filtered = filtered.filter((item) =>
        (mockRecentInspectionFacilityTypeById[item.id] ?? '').startsWith(facilityType),
      );
    }
    if (query) {
      filtered = filtered.filter(
        (item) =>
          item.facilityName.toLowerCase().includes(query) ||
          item.inspector.toLowerCase().includes(query),
      );
    }

    const start = page * size;
    const content = filtered.slice(start, start + size);
    const body: ApiResponse<PageResponse<RecentInspectionItem>> = {
      success: true,
      data: { content, page, totalElements: filtered.length },
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/dashboard/upcoming-inspections', () => {
    const body: ApiResponse<UpcomingInspectionItem[]> = {
      success: true,
      data: mockUpcomingInspections,
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
