import { useQuery } from '@tanstack/react-query';
import { statisticsApi } from '../api/statisticsApi';
import type { StatisticsFilterParams } from '../types';

export function useStatisticsGradeDistribution(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'grade-distribution', params] as const,
    queryFn: () => statisticsApi.getGradeDistribution(params).then((res) => res.data),
  });
}
