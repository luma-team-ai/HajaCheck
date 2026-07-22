import { useQuery } from '@tanstack/react-query';
import { facilityApi } from '../api/facilityApi';

export function useFacility(id: number) {
  const isValidId = Number.isInteger(id) && id > 0;
  return useQuery({
    queryKey: ['facility', 'detail', id] as const,
    queryFn: () => facilityApi.getDetail(id).then((res) => res.data),
    enabled: isValidId,
  });
}
