import { useQuery } from '@tanstack/react-query';
import { facilityComparisonApi } from '../api/facilityComparisonApi';

// beforeCycle/afterCycle 생략(#1157) — 화면 최초 진입 시 그 시설물의 유효한 회차를 아직 모르므로,
// 서버가 자동 대체한 회차로 첫 응답을 받는다(FacilityInspectionComparePage가 이후 선택 상태를 동기화).
export function useFacilityComparison(facilityId: string, beforeCycle?: number, afterCycle?: number) {
  return useQuery({
    queryKey: ['facility-comparison', facilityId, beforeCycle, afterCycle],
    queryFn: () =>
      facilityComparisonApi.getComparison(facilityId, beforeCycle, afterCycle).then((res) => res.data),
  });
}