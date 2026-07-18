import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';

export function useRecentInspections() {
  return useQuery({
    queryKey: ['dashboard', 'recent-inspections'],
    queryFn: () => dashboardApi.getRecentInspections().then((res) => res.data),
  });
}
