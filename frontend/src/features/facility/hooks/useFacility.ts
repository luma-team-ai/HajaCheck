import { useQuery } from '@tanstack/react-query';
import { facilityApi } from '../api/facilityApi';

export function useFacility(id: number) {
  return useQuery({
    queryKey: ['facility', 'detail', id] as const,
    queryFn: () => facilityApi.getDetail(id).then((res) => res.data),
  });
}
