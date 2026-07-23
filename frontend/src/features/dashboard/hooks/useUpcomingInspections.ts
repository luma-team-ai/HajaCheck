import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';

export function useUpcomingInspections() {
  return useQuery({
    queryKey: ['dashboard', 'upcoming-inspections'],
    queryFn: () => dashboardApi.getUpcomingInspections().then((res) => res.data),
  });
}
