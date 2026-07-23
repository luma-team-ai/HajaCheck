import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { planPolicyApi } from '../api/planPolicyApi';
import type { PlanPolicyApiItem } from '../planPolicy.mapper';

// 플랜 정책 설정 초기값 — plans 테이블에서 가져온다(#624 후속, 사용자 지시. 이전엔 프론트 하드코딩값
// PLAN_POLICY_DEFAULTS를 썼다).
export function usePlanPolicies() {
  return useQuery<PlanPolicyApiItem[], ApiError>({
    queryKey: ['platform-admin', 'plans', 'policy'],
    queryFn: () => planPolicyApi.getCatalog().then((res) => res.data.plans),
  });
}
