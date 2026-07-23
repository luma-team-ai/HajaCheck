import { api } from '../../../shared/api/axios';
import type { PlanQuotaListParams, PlanQuotaListResponse } from '../planQuota.types';

// 플랫폼 관리자 플랜·쿼터 API — 회사 스코프 없이 전사 데이터를 다룬다(#624, 백엔드 별도 구현).
// 계약 확정 시 경로·파라미터명을 여기서 한 번만 맞추면 된다(platformAdminUserApi.ts와 동일 전략).
export const planQuotaApi = {
  getUsers: (params: PlanQuotaListParams) =>
    api.get<PlanQuotaListResponse>('/platform-admin/plans-quota', { params }),
};
