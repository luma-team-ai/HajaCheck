import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import type { AdminPlanCatalogResponse } from '../planQuota.types';

// 요금제 카탈로그 — 3개 플랜 고정값이라 매 렌더 재조회할 필요가 없어 staleTime을 길게 둔다.
export function useAdminPlanCatalog() {
  return useQuery<AdminPlanCatalogResponse, ApiError>({
    queryKey: ['platform-admin', 'plans', 'catalog'],
    queryFn: () => adminPlanApi.getCatalog().then((res) => res.data),
    staleTime: 5 * 60 * 1000,
  });
}
