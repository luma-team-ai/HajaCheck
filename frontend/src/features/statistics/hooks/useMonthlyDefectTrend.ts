import { useQuery } from '@tanstack/react-query';
import { statisticsApi } from '../api/statisticsApi';
import type { StatisticsFilterParams } from '../types';

export function useMonthlyDefectTrend(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'monthly-trend', params] as const,
    queryFn: () => statisticsApi.getMonthlyTrend(params).then((res) => res.data),
  });
}
