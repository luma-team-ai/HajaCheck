import { useQuery } from '@tanstack/react-query';
import { statisticsApi } from '../api/statisticsApi';
import type { StatisticsFilterParams } from '../types';

export function useStatisticsSummary(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'summary', params] as const,
    queryFn: () => statisticsApi.getSummary(params).then((res) => res.data),
  });
}
