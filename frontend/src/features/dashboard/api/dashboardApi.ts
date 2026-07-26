import { api } from '../../../shared/api/axios';
import { aiClient } from '../../../shared/api/aiClient';
import type { PageResponse } from '../../../shared/api/types';
import type {
  AiBriefing,
  DashboardSummary,
  GradeDistributionItem,
  PendingPriorityItem,
  RecentInspectionItem,
  RecentInspectionsSearchFilters,
  UpcomingInspectionItem,
} from '../types';

export const dashboardApi = {
  getSummary: () => api.get<DashboardSummary>('/dashboard/summary'),
  getGradeDistribution: () => api.get<GradeDistributionItem[]>('/dashboard/grade-distribution'),
  getPendingPriority: () => api.get<PendingPriorityItem[]>('/dashboard/pending-priority'),
  getRecentInspections: () => api.get<RecentInspectionItem[]>('/dashboard/recent-inspections'),
  // "최근 점검 전체보기"(신규) — 기존 위젯(getRecentInspections)과 별개 엔드포인트라 별도 메서드로 둔다.
  // 필터 바의 "시설물 종류"는 특정 시설물 select가 아니라 카테고리 문자열(facilityType)이라 별도
  // 옵션 조회 API가 필요 없다(로컬 상수, RecentInspectionsFullListPage.tsx 참고).
  searchRecentInspections: (filters: RecentInspectionsSearchFilters = {}) =>
    api.get<PageResponse<RecentInspectionItem>>('/dashboard/recent-inspections/search', {
      params: filters,
    }),
  // dev-03-02, #469 — 다음 점검일 도래(days/limit 쿼리, 기본값은 BE가 관리)
  getUpcomingInspections: () =>
    api.get<UpcomingInspectionItem[]>('/dashboard/upcoming-inspections'),
  // ai-server #108 — 대시보드 현황 기반 AI 주간 브리핑
  getBriefing: () => aiClient.post<AiBriefing>('/briefing'),
};
