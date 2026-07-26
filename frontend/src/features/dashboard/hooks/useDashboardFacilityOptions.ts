import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';

// "최근 점검 전체보기" 필터 바의 시설물 select 옵션 — defect feature의
// useInspectionFacilityOptions와 동일 패턴(GET /api/facilities 재사용, feature 간 직접 import 금지).
export function useDashboardFacilityOptions() {
  return useQuery({
    queryKey: ['dashboard', 'facility-options'] as const,
    queryFn: () => dashboardApi.listFacilityOptions().then((res) => res.data),
  });
}
