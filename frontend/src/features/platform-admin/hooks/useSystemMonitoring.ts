import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { monitoringApi } from '../api/monitoringApi';
import type { SystemMonitoringResponse } from '../monitoring.types';

// 목 폴백은 두지 않는다 — 개발 환경은 MSW(monitoringApi.handlers)가 응답하고, 그 밖의 실패는
// 화면이 에러 상태로 정직하게 노출해야 한다(useServiceStats와 동일 전략).
export function useSystemMonitoring() {
  return useQuery<SystemMonitoringResponse, ApiError>({
    queryKey: ['platform-admin', 'monitoring'],
    queryFn: () => monitoringApi.getMonitoring().then((res) => res.data),
  });
}
