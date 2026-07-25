import { useQuery } from '@tanstack/react-query';
import { statisticsApi } from '../api/statisticsApi';
import type { StatisticsFilterParams } from '../types';

export function useFacilitySummary(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'facility-summary', params] as const,
    queryFn: () => statisticsApi.getFacilitySummary(params).then((res) => res.data),
  });
}
