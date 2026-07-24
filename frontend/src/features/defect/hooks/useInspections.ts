import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';
import type { InspectionListFilters } from '../types';

// 점검(Inspection) 단위 목록 조회 — HAJA-393/394, #725/#726. useDefects(defectKeys)와 동일 패턴이되
// 별도 키 네임스페이스('inspection-list')를 쓴다.
export const inspectionListKeys = {
  list: (filters: InspectionListFilters) => ['defect', 'inspection-list', filters] as const,
};

export function useInspections(filters: InspectionListFilters = {}) {
  return useQuery({
    queryKey: inspectionListKeys.list(filters),
    queryFn: () => defectApi.getInspections(filters).then((res) => res.data),
  });
}
