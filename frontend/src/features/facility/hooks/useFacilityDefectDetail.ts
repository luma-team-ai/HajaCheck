import { useQuery } from '@tanstack/react-query';
import { facilityDefectApi } from '../api/facilityDefectApi';

export const facilityDefectDetailKey = (facilityId: string) =>
  ['facility-defect-detail', facilityId] as const;

export function useFacilityDefectDetail(facilityId: string) {
  return useQuery({
    queryKey: facilityDefectDetailKey(facilityId),
    queryFn: () => facilityDefectApi.getDetail(facilityId).then((res) => res.data),
  });
}