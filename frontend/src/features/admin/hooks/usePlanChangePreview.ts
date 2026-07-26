import { useQuery } from '@tanstack/react-query';
import type { ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import type { PlanChangePreviewResponse } from '../planQuota.types';
import type { AdminUserPlan } from '../types';

/**
 * GET /api/admin/plan/change-preview — 하향으로 정지될 구성원·읽기전용 시설물을 부작용 없이
 * 미리본다(#890). keepUserIds가 바뀌면(관리자가 유지할 구성원을 직접 고르면) 새 쿼리 키로 다시
 * 조회해 미리보기와 실제 전환 결과가 항상 같은 선택을 반영하게 한다(#890 Phase 2).
 *
 * @param enabled 플랜 변경 확인 모달이 열려 있을 때만 조회한다(불필요한 요청 방지).
 */
export function usePlanChangePreview(
  planName: AdminUserPlan | null,
  keepUserIds: number[],
  enabled: boolean,
) {
  return useQuery<PlanChangePreviewResponse, ApiError>({
    queryKey: ['admin', 'plan', 'change-preview', planName, keepUserIds],
    queryFn: () => adminPlanApi.previewChange(planName as AdminUserPlan, keepUserIds).then((res) => res.data),
    enabled: enabled && planName !== null,
  });
}
