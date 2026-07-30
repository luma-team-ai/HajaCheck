import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import type { AdminScheduledPlanChange } from '../planQuota.types';

/**
 * DELETE /api/admin/plan/scheduled-change — 대기 중인 하향 예약을 취소한다(#1105 / HAJA-526, #1191).
 * "현재 플랜" 카드의 예약 배너 취소 버튼이 이 훅을 쓴다.
 */
export function useCancelScheduledPlanChange() {
  const queryClient = useQueryClient();

  const mutation = useMutation<AdminScheduledPlanChange, ApiError, void>({
    mutationFn: () => adminPlanApi.cancelScheduledChange().then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'plan', 'current'] });
    },
  });

  return {
    cancelScheduledChange: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
