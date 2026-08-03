import { useQuery } from '@tanstack/react-query';
import { planApi, planQueryKeys } from '../api/planApi';

export type AiAddonAvailability = 'checking' | 'available' | 'unavailable' | 'unknown';

export const AI_ADDON_UNAVAILABLE_MESSAGE =
  'AI 자연어 검색은 AI 부가 기능이 포함된 플랜에서만 사용할 수 있습니다.';

export function useAiAddonAvailability(): AiAddonAvailability {
  const currentPlanQuery = useQuery({
    queryKey: planQueryKeys.current,
    queryFn: ({ signal }) => planApi.getCurrentPlan(signal).then((response) => response.data),
    staleTime: 5 * 60 * 1000,
    retry: false,
  });
  const catalogQuery = useQuery({
    queryKey: planQueryKeys.catalog,
    queryFn: ({ signal }) => planApi.getPlans(signal).then((response) => response.data.plans),
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  if (currentPlanQuery.isPending || catalogQuery.isPending) {
    return 'checking';
  }

  if (currentPlanQuery.isError || catalogQuery.isError) {
    return 'unknown';
  }

  const currentPolicy = catalogQuery.data.find(
    (plan) => plan.name === currentPlanQuery.data.plan.name,
  );

  if (!currentPolicy) {
    return 'unknown';
  }

  return currentPolicy.hasAiAddon ? 'available' : 'unavailable';
}
