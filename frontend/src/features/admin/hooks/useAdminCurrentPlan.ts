import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import type { AdminCurrentPlanResponse } from '../planQuota.types';

// GET /api/admin/plan — 내 회사의 현재 구독(currentPeriodEnd·scheduledChange 포함, #1105 / HAJA-526).
// "현재 플랜" 카드의 하향 예약 배너·취소는 이 조회 하나로 판정한다(scheduledChange 존재=예약 중).
export function useAdminCurrentPlan() {
  return useQuery<AdminCurrentPlanResponse, ApiError>({
    queryKey: ['admin', 'plan', 'current'],
    queryFn: () => adminPlanApi.getCurrentPlan().then((res) => res.data),
  });
}
