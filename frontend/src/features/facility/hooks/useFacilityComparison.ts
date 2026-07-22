import { useQuery } from '@tanstack/react-query';
import { facilityComparisonApi } from '../api/facilityComparisonApi';

export function useFacilityComparison(facilityId: string, beforeCycle: number, afterCycle: number) {
  return useQuery({
    queryKey: ['facility-comparison', facilityId, beforeCycle, afterCycle],
    queryFn: () =>
      facilityComparisonApi.getComparison(facilityId, beforeCycle, afterCycle).then((res) => res.data),
  });
}