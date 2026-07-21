import { useQuery } from '@tanstack/react-query';
import { getFacilityOverview } from '../mocks/facilityOverview.mock';

// 실 목록/집계 엔드포인트가 아직 없어 feature 로컬 목 모듈을 Promise로 감싸 TanStack Query
// 관례를 그대로 따른다(facility feature의 useFacilityInspectionOverview와 동일 패턴).
export function useFacilityOverview(facilityId: number) {
  return useQuery({
    queryKey: ['inspection', 'facility-overview', facilityId] as const,
    queryFn: () => Promise.resolve(getFacilityOverview(facilityId)),
  });
}
