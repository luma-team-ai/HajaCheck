import { keepPreviousData, useQuery } from '@tanstack/react-query';
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
  // 쿼리 키에 넣기 전에 정렬 — 같은 선택 집합이라도 체크 순서가 다르면 배열 순서가 달라져 캐시가
  // 미스나는 것을 막는다(#930 재검토 F-12). 정렬은 서버로 보내는 실제 요청 인자(원래 순서)에는
  // 영향을 주지 않는다 — queryFn 안에서 별도로 캡처된 keepUserIds를 그대로 전달한다.
  const sortedKeepUserIds = [...keepUserIds].sort((a, b) => a - b);

  return useQuery<PlanChangePreviewResponse, ApiError>({
    queryKey: ['admin', 'plan', 'change-preview', planName, sortedKeepUserIds],
    queryFn: () => adminPlanApi.previewChange(planName as AdminUserPlan, keepUserIds).then((res) => res.data),
    enabled: enabled && planName !== null,
    // 재조회 중에도 이전 미리보기 값을 유지 — "계산 중..." 깜빡임과, 그 창에 체크박스가 preview
    // 없음(전원 checked로 오표시)으로 떨어지는 문제를 함께 막는다(#930 재검토 F-3).
    placeholderData: keepPreviousData,
  });
}
