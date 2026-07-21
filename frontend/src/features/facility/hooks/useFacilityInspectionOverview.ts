import { useQuery } from '@tanstack/react-query';
import { getFacilityInspectionOverview } from '../mocks/facilityInspectionOverview.mock';

// 실 목록/집계 엔드포인트가 아직 없어 feature 로컬 목 모듈을 Promise로 감싸 TanStack Query
// 관례를 그대로 따른다(useInspectionCycleStatusRows와 동일 패턴). 실연동 시 이 queryFn만
// facilityApi 호출로 교체하면 되도록 훅 시그니처는 그대로 둔다.
export function useFacilityInspectionOverview(facilityId: number) {
  return useQuery({
    queryKey: ['facility', facilityId, 'inspection-overview'] as const,
    queryFn: () => Promise.resolve(getFacilityInspectionOverview(facilityId)),
  });
}
