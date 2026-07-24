import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';

// 점검 목록(HAJA-393/394) 필터의 시설물 select 옵션 — GET /api/facilities.
export function useInspectionFacilityOptions() {
  return useQuery({
    queryKey: ['defect', 'inspection-facility-options'] as const,
    queryFn: () => defectApi.listFacilityOptions().then((res) => res.data),
  });
}
