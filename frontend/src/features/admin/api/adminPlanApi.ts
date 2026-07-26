import { api } from '../../../shared/api/axios';
import type {
  AdminCurrentPlanResponse,
  AdminPlanCatalogResponse,
  PlanChangePreviewResponse,
  PlanChangeRequestPayload,
} from '../planQuota.types';
import type { AdminUserPlan } from '../types';

export const adminPlanApi = {
  // 관리자 요금제 카탈로그 — "현재 플랜" 카드의 가격·한도는 이 응답(plans 테이블)에서 가져온다.
  getCatalog: () => api.get<AdminPlanCatalogResponse>('/admin/plans'),

  // 내 회사의 현재 구독+이번 달 사용량 — "사용자 등록" 버튼 클릭 시 좌석 잔여 확인(#872 후속)에 쓴다.
  getCurrentPlan: () => api.get<AdminCurrentPlanResponse>('/admin/plan'),

  // GET /api/admin/plan/change-preview — 하향 시 정지될 구성원·읽기전용 시설물을 부작용 없이
  // 미리본다(#890). keepUserIds는 백엔드 `@RequestParam List<Long>` 바인딩과 맞추기 위해 대괄호
  // 없는 반복 키(keepUserIds=1&keepUserIds=2)로 보낸다 — axios 기본 배열 직렬화(keepUserIds[]=1)는
  // 백엔드가 빈 리스트로 받는 위험이 있다(defectApi.ts의 동일 전략, `indexes: null`).
  previewChange: (planName: AdminUserPlan, keepUserIds?: number[]) =>
    api.get<PlanChangePreviewResponse>('/admin/plan/change-preview', {
      params: {
        planName,
        ...(keepUserIds && keepUserIds.length > 0 ? { keepUserIds } : {}),
      },
      paramsSerializer: { indexes: null },
    }),

  // PATCH /api/admin/plan — 회사 구독 요금제를 즉시 변경한다(무결제, 회사 owner 전용). confirmOverflow·
  // keepUserIds는 #890 Phase 1/2 — 응답 바디는 화면이 성공 여부·재조회 트리거로만 쓰므로 세부 타입을
  // 두지 않는다(변경 후 usePlanQuotaUsers/useAdminPlanCatalog를 무효화해 실제 값은 그쪽에서 다시 받는다).
  changePlan: (payload: PlanChangeRequestPayload) => api.patch<unknown>('/admin/plan', payload),
};
