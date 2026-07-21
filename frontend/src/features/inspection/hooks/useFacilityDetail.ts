import { useQuery } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';

export function useFacilityDetail(facilityId: number) {
  return useQuery({
    queryKey: ['inspection', 'facility-detail', facilityId] as const,
    queryFn: () => inspectionApi.getFacilityDetail(facilityId).then((res) => res.data),
  });
}
