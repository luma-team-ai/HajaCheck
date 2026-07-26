import { useQuery } from '@tanstack/react-query';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';
import { statisticsApi } from '../api/statisticsApi';
import { mockDefectTypeDistribution } from '../mocks/statistics.mock';
import type { StatisticsFilterParams } from '../types';

export function useDefectTypeDistribution(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'defect-type-distribution', params] as const,
    queryFn: () =>
      hybridFetchFallback({
        fetcher: () => statisticsApi.getDefectTypeDistribution(params).then((res) => res.data),
        fallback: mockDefectTypeDistribution,
        fallbackOnEmptyArray: true,
      }),
  });
}

