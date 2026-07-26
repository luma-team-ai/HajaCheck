import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import type { PlanChangeRequestPayload } from '../planQuota.types';

/**
 * PATCH /api/admin/plan — 회사 구독 요금제를 즉시 변경한다(#507, 하향 확인·keepUserIds는 #890 Phase 1/2).
 * 성공 시 쿼터 목록·요금제 카탈로그를 무효화해 정지된 멤버·바뀐 플랜이 화면에 바로 반영되게 한다.
 */
export function useChangePlan() {
  const queryClient = useQueryClient();

  const mutation = useMutation<unknown, ApiError, PlanChangeRequestPayload>({
    mutationFn: (payload) => adminPlanApi.changePlan(payload).then((res) => res.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'plan-quota'] });
      queryClient.invalidateQueries({ queryKey: ['admin', 'plan', 'change-preview'] });
    },
  });

  return {
    changePlan: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
