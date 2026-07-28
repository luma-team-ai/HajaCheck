import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { facilityApi } from '../api/facilityApi';
import { inspectionCycleStatusListKey } from './useInspectionCycleStatusRows';
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
// 좌측 카드 "다음 점검일"을 갱신하고, 시설물 목록/상세/현황 쿼리를 무효화한다.
//
// #1136 — 현황 테이블(useInspectionCycleStatusRows)이 이제 실 GET /api/facilities/status를 읽으므로
// (이전엔 별도 mutable 목 store를 따로 갱신해야 좌/우가 어긋나지 않았다 — react-reviewer P1),
// invalidateQueries만으로 재조회 시 자연히 최신값을 받는다. 별도 store 갱신 호출이 더 이상 필요 없다.
export function useSetInspectionSchedule() {
  const queryClient = useQueryClient();

  const mutation = useMutation<SetFacilityScheduleResponse, ApiError, Variables>({
    mutationFn: ({ facilityId, body }) => facilityApi.setSchedule(facilityId, body).then((res) => res.data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: facilityKeys.list });
      queryClient.invalidateQueries({ queryKey: facilityDetailKey(variables.facilityId) });
      queryClient.invalidateQueries({ queryKey: inspectionCycleStatusListKey });
    },
  });

  return {
    setSchedule: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
