import { useQuery } from '@tanstack/react-query';
import { statisticsApi } from '../api/statisticsApi';
import type { StatisticsFilterParams } from '../types';

export function useDefectTypeDistribution(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'defect-type-distribution', params] as const,
    queryFn: () => statisticsApi.getDefectTypeDistribution(params).then((res) => res.data),
  });
}
