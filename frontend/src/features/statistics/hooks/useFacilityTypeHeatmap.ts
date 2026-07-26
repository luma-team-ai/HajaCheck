import { useQuery } from '@tanstack/react-query';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';
import { statisticsApi } from '../api/statisticsApi';
import { mockFacilityTypeHeatmap } from '../mocks/statistics.mock';
import type { StatisticsFilterParams } from '../types';

export function useFacilityTypeHeatmap(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'facility-type-heatmap', params] as const,
    queryFn: () =>
      hybridFetchFallback({
        fetcher: () => statisticsApi.getFacilityTypeHeatmap(params).then((res) => res.data),
        fallback: mockFacilityTypeHeatmap,
        fallbackOnEmptyArray: true,
      }),
  });
}

