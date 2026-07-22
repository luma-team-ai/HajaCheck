import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';
import type { DefectListFilters } from '../types';

// useDefect(상세)에서도 동일 키 네임스페이스로 무효화할 수 있도록 공유
export const defectKeys = {
  list: (filters: DefectListFilters) => ['defect', 'list', filters] as const,
  detail: (id: number) => ['defect', 'detail', id] as const,
};

export function useDefects(filters: DefectListFilters = {}) {
  return useQuery({
    queryKey: defectKeys.list(filters),
    queryFn: () => defectApi.getList(filters).then((res) => res.data),
  });
}
