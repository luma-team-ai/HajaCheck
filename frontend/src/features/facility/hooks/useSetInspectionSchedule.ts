import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { facilityApi } from '../api/facilityApi';
import type { SetFacilityScheduleRequest, SetFacilityScheduleResponse } from '../types';
import { facilityKeys } from './useFacilities';

interface Variables {
  facilityId: number;
  body: SetFacilityScheduleRequest;
}

// 상세 조회 훅(useFacilityDetail)이 아직 없어 무효화 키만 이 훅 스코프에서 정의 —
// 향후 시설물 상세 화면이 상세 쿼리를 도입하면 동일 키 규칙(['facility','detail',id])을 따를 것.
const facilityDetailKey = (id: number) => ['facility', 'detail', id] as const;

// 저장 버튼 → 실 API POST /api/facilities/{id}/schedule (handoff §2·§3). 응답 nextInspectionDueAt으로
// 좌측 카드 "다음 점검일"을 갱신하고, 시설물 목록/상세 쿼리를 무효화해 현황 테이블에도 반영한다.
export function useSetInspectionSchedule() {
  const queryClient = useQueryClient();

  const mutation = useMutation<SetFacilityScheduleResponse, ApiError, Variables>({
    mutationFn: ({ facilityId, body }) => facilityApi.setSchedule(facilityId, body).then((res) => res.data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: facilityKeys.list });
      queryClient.invalidateQueries({ queryKey: facilityDetailKey(variables.facilityId) });
    },
  });

  return {
    setSchedule: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
