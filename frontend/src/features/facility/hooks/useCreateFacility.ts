import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { facilityApi } from '../api/facilityApi';
import type { CreateFacilityRequest, Facility } from '../types';
import { facilityKeys } from './useFacilities';

export function useCreateFacility() {
  const queryClient = useQueryClient();

  const mutation = useMutation<Facility, ApiError, CreateFacilityRequest>({
    mutationFn: (body) => facilityApi.create(body).then((res) => res.data),
    onSuccess: () => {
      // 등록 성공 시 목록 쿼리 무효화 — 새 시설물이 즉시 테이블에 반영되도록
      queryClient.invalidateQueries({ queryKey: facilityKeys.list });
    },
  });

  return {
    createFacility: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
