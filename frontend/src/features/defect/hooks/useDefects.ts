import { useQuery } from '@tanstack/react-query';
import { defectApi } from '../api/defectApi';
import type { DefectListFilters } from '../types';

// useDefect(상세)에서도 동일 키 네임스페이스로 무효화할 수 있도록 공유
export const defectKeys = {
  list: (filters: DefectListFilters) => ['defect', 'list', filters] as const,
  detail: (id: number) => ['defect', 'detail', id] as const,
};

// options.enabled — 응답이 실제로 쓰이지 않는 화면(예: DefectListPage의 "목록 보기" 탭 활성 시)에서
// 불필요한 GET /api/defects 요청을 막기 위해 호출부가 명시적으로 끌 수 있게 한다(PR머신 P2 지적).
// 기본값 true(기존 동작 유지) — 지정하지 않은 기존 호출부는 그대로 항상 조회한다.
export function useDefects(filters: DefectListFilters = {}, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: defectKeys.list(filters),
    queryFn: () => defectApi.getList(filters).then((res) => res.data),
    enabled: options?.enabled ?? true,
  });
}
