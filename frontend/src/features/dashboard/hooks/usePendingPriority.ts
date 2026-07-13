import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';

export function usePendingPriority() {
  return useQuery({
    queryKey: ['dashboard', 'pending-priority'],
    queryFn: () => dashboardApi.getPendingPriority().then((res) => res.data),
  });
}
