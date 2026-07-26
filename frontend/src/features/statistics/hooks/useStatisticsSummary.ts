import { useQuery } from '@tanstack/react-query';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';
import { statisticsApi } from '../api/statisticsApi';
import { mockStatisticsKpiSummary } from '../mocks/statistics.mock';
import type { StatisticsFilterParams } from '../types';

export function useStatisticsSummary(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'summary', params] as const,
    queryFn: () =>
      hybridFetchFallback({
        fetcher: () => statisticsApi.getSummary(params).then((res) => res.data),
        fallback: mockStatisticsKpiSummary,
        fallbackOnEmptyArray: false,
      }),
  });
}

