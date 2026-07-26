import { useQuery } from '@tanstack/react-query';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';
import { statisticsApi } from '../api/statisticsApi';
import { mockMonthlyDefectTrend } from '../mocks/statistics.mock';
import type { StatisticsFilterParams } from '../types';

export function useMonthlyDefectTrend(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'monthly-trend', params] as const,
    queryFn: () =>
      hybridFetchFallback({
        fetcher: () => statisticsApi.getMonthlyTrend(params).then((res) => res.data),
        fallback: mockMonthlyDefectTrend,
        fallbackOnEmptyArray: true,
      }),
  });
}

