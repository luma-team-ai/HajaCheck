import { useQuery } from '@tanstack/react-query';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';
import { statisticsApi } from '../api/statisticsApi';
import { mockFacilitySummary } from '../mocks/statistics.mock';
import type { StatisticsFilterParams } from '../types';

export function useFacilitySummary(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'facility-summary', params] as const,
    queryFn: () =>
      hybridFetchFallback({
        fetcher: () => statisticsApi.getFacilitySummary(params).then((res) => res.data),
        fallback: mockFacilitySummary,
        fallbackOnEmptyArray: true,
      }),
  });
}

