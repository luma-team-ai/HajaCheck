import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';
import { mockPendingPriority } from '../mocks/dashboard.mock';
import { fetchWithFallback } from '../utils/fetchWithFallback';

// 백엔드 미구현 시 예제 데이터 폴백(HAJA-17) — 실 API 실패 시 mock 데이터로 대체해 위젯 렌더 보장
export function usePendingPriority() {
  return useQuery({
    queryKey: ['dashboard', 'pending-priority'],
    queryFn: () =>
      fetchWithFallback(() => dashboardApi.getPendingPriority().then((res) => res.data), mockPendingPriority),
  });
}
