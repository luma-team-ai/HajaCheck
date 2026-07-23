import { useQuery } from '@tanstack/react-query';
import { facilityAssigneeApi } from '../api/facilityAssigneeApi';

// inspection/hooks/useFacilityOptions.ts와 동일 패턴 — 담당자 select 옵션 조회(#629, 실 API 없음).
export function useFacilityAssignableUsers() {
  return useQuery({
    queryKey: ['facility', 'assignable-users'] as const,
    queryFn: () => facilityAssigneeApi.listAssignableUsers().then((res) => res.data),
  });
}
