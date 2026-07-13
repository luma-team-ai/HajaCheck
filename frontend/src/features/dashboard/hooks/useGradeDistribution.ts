import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/dashboardApi';

export function useGradeDistribution() {
  return useQuery({
    queryKey: ['dashboard', 'grade-distribution'],
    queryFn: () => dashboardApi.getGradeDistribution().then((res) => res.data),
  });
}
