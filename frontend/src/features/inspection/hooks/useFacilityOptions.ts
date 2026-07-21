import { useQuery } from '@tanstack/react-query';
import { inspectionApi } from '../api/inspectionApi';

export function useFacilityOptions() {
  return useQuery({
    queryKey: ['inspection', 'facility-options'] as const,
    queryFn: () => inspectionApi.listFacilityOptions().then((res) => res.data),
  });
}
