import { useQuery } from '@tanstack/react-query';
import { facilityDefectApi } from '../api/facilityDefectApi';

// 쿼리 키 인자는 defectId다(facilityId 아님) — GET /api/defects/{id} 식별자와 정합.
export const facilityDefectDetailKey = (defectId: string) =>
  ['facility-defect-detail', defectId] as const;

export function useFacilityDefectDetail(defectId: string) {
  return useQuery({
    queryKey: facilityDefectDetailKey(defectId),
    queryFn: () => facilityDefectApi.getDetail(defectId).then((res) => res.data),
  });
}