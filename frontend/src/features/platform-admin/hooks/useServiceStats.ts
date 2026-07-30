import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { statsApi } from '../api/statsApi';
import type { ServiceStatsResponse } from '../stats.types';

// 목 폴백은 두지 않는다 — 개발 환경은 MSW(statsApi.handlers)가 응답하고, 그 밖의 실패는
// 화면이 에러 상태로 정직하게 노출해야 한다(usePlanQuotaUsers와 동일 전략).
export function useServiceStats() {
  return useQuery<ServiceStatsResponse, ApiError>({
    queryKey: ['platform-admin', 'stats'],
    queryFn: () => statsApi.getStats().then((res) => res.data),
  });
}
