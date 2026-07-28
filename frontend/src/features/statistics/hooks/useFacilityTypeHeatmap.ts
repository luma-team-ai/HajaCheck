import { useQuery } from '@tanstack/react-query';
import { statisticsApi } from '../api/statisticsApi';
import type { StatisticsFilterParams } from '../types';

export function useFacilityTypeHeatmap(params?: StatisticsFilterParams) {
  return useQuery({
    queryKey: ['statistics', 'facility-type-heatmap', params] as const,
    queryFn: () => statisticsApi.getFacilityTypeHeatmap(params).then((res) => res.data),
  });
}
