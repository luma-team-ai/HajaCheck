import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import type { AdminScheduledPlanChange, AdminScheduledPlanChangeRequestPayload } from '../planQuota.types';

/**
 * POST /api/admin/plan/scheduled-change — FREE 하향을 다음 결제 주기에 적용되도록 예약한다
 * (#1105 / HAJA-526, #1191). 예약 시점에는 요금제·좌석이 바뀌지 않으므로 plan-quota 목록은
 * 무효화하지 않고, "현재 플랜" 카드가 읽는 조회만 갱신해 배너를 즉시 반영한다.
 */
export function useScheduleDowngrade() {
  const queryClient = useQueryClient();

  const mutation = useMutation<AdminScheduledPlanChange, ApiError, AdminScheduledPlanChangeRequestPayload>({
    mutationFn: (payload) => adminPlanApi.scheduleChange(payload).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'plan', 'current'] });
    },
  });

  return {
    scheduleDowngrade: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
