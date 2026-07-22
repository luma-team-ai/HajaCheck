import { useQuery } from '@tanstack/react-query';
import { facilityDefectApi } from '../api/facilityDefectApi';

export function useFacilityDefectActivityLog(facilityId: string) {
  return useQuery({
    queryKey: ['facility-defect-activity', facilityId],
    queryFn: () => facilityDefectApi.getActivityLog(facilityId).then((res) => res.data),
  });
}