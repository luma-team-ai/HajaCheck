import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { facilityApi } from '../api/facilityApi';
import { inspectionNotificationSettingsKey } from './useInspectionNotificationSettings';
import type { InspectionNotificationSettings } from '../types';

interface Variables {
  facilityId: number;
  body: InspectionNotificationSettings;
}

// 저장 버튼 → 실 API PUT /api/facilities/{id}/notification-settings(#540 ③). 성공 시 해당 시설물의
// 조회 쿼리 캐시를 응답값으로 바로 채워 넣어(setQueryData) 재조회 없이도 화면이 최신값을 반영한다.
export function useSaveInspectionNotificationSettings() {
  const queryClient = useQueryClient();

  const mutation = useMutation<InspectionNotificationSettings, ApiError, Variables>({
    mutationFn: ({ facilityId, body }) =>
      facilityApi.setNotificationSettings(facilityId, body).then((res) => res.data),
    onSuccess: (data, variables) => {
      queryClient.setQueryData(inspectionNotificationSettingsKey(variables.facilityId), data);
    },
  });

  return {
    saveNotificationSettings: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}