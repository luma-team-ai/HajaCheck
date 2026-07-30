import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { planPolicyApi } from '../api/planPolicyApi';
import type { PlanPolicyApiItem } from '../planPolicy.mapper';

// 플랜 정책 저장 — 3개 플랜을 한 번에 교체한다(백엔드 계약). 성공 시 정책 캐시뿐 아니라 plans-quota
// 목록도 무효화한다 — 한도(quotaLimit) 표시가 plans 테이블에서 나오므로 정책이 바뀌면 같이 갱신돼야 한다.
export function useUpdatePlanPolicies() {
  const queryClient = useQueryClient();

  const mutation = useMutation<PlanPolicyApiItem[], ApiError, PlanPolicyApiItem[]>({
    mutationFn: (plans) => planPolicyApi.updateCatalog(plans).then((res) => res.data.plans),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['platform-admin', 'plans'] });
      queryClient.invalidateQueries({ queryKey: ['platform-admin', 'plan-quota'] });
    },
  });

  return {
    updatePolicies: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
