import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { facilityApi } from '../api/facilityApi';
import type { CreateFacilityRequest, Facility } from '../types';
import { facilityKeys } from './useFacilities';

interface UpdateFacilityVariables {
  id: number;
  body: CreateFacilityRequest;
}

// 시설물 수정(PUT /api/facilities/{id}) — FacilityDetailPage의 수정 모달 저장 버튼 전용(#1681).
// PUT은 전체 교체 계약이라 body는 FacilityFormModal(mode='edit')이 조립한 전체 필드를 담는다.
export function useUpdateFacility() {
  const queryClient = useQueryClient();

  const mutation = useMutation<Facility, ApiError, UpdateFacilityVariables>({
    mutationFn: ({ id, body }) => facilityApi.update(id, body).then((res) => res.data),
    onSuccess: (_updatedFacility, variables) => {
      // 목록(list)·상세(detail) 캐시를 모두 무효화 — 수정 결과가 두 화면 모두에 즉시 반영되도록
      // (useCreateFacility의 목록 무효화 패턴과 동일 — useFacility.ts의 쿼리 키와 정합시켜야 한다).
      queryClient.invalidateQueries({ queryKey: facilityKeys.list });
      queryClient.invalidateQueries({ queryKey: ['facility', 'detail', variables.id] });
    },
  });

  return {
    updateFacility: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    // 모달을 닫을 때 이전 실패의 에러 메시지가 다음 오픈 때 재노출되지 않도록 호출부에서 초기화용으로 사용
    resetError: mutation.reset,
  };
}
