import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';
import type { RecentInspectionsSearchFilters } from '../types';

// "최근 점검 전체보기"(신규) — GET /api/dashboard/recent-inspections/search.
// 기존 useRecentInspections(위젯, 상위 10건 고정 배열)과 별개 훅 — 페이지네이션+필터 상태를 쿼리 키에 반영.
export const recentInspectionsListKeys = {
  list: (filters: RecentInspectionsSearchFilters) =>
    ['dashboard', 'recent-inspections', 'search', filters] as const,
};

export function useRecentInspectionsList(filters: RecentInspectionsSearchFilters = {}) {
  return useQuery({
    queryKey: recentInspectionsListKeys.list(filters),
    queryFn: () => dashboardApi.searchRecentInspections(filters).then((res) => res.data),
  });
}
