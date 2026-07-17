import { useQuery } from '@tanstack/react-query';
import { facilityApi } from '../api/facilityApi';

// 다른 훅(useCreateFacility 등)에서도 동일 쿼리 키로 무효화할 수 있도록 공유
export const facilityKeys = {
  list: ['facility', 'list'] as const,
};

export function useFacilities() {
  return useQuery({
    queryKey: facilityKeys.list,
    queryFn: () => facilityApi.getList().then((res) => res.data),
  });
}
