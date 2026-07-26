import { useQuery } from '@tanstack/react-query';
import { hybridFetchFallback } from '../../../shared/utils/hybridFetchFallback';
import { statisticsApi } from '../api/statisticsApi';
import { mockGradeDistribution } from '../mocks/statistics.mock';
import type { StatisticsFilterParams } from '../types';

export function useStatisticsGradeDistribution(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'grade-distribution', params] as const,
    queryFn: () =>
      hybridFetchFallback({
        fetcher: () => statisticsApi.getGradeDistribution(params).then((res) => res.data),
        fallback: mockGradeDistribution,
        fallbackOnEmptyArray: true,
      }),
  });
}

