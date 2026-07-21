import { api } from '../../../shared/api/axios';
import type { PlanQuotaListParams, PlanQuotaListResponse } from '../planQuota.types';

// 관리자 플랜·쿼터 API — 백엔드 계약(contract.md)에 아직 /admin/plan-quota가 없어 경로는 선제 정의.
// 계약 확정 시 경로·파라미터명을 여기서 한 번만 맞추면 된다(adminApi.ts와 동일 전략).
export const planQuotaApi = {
  getUsers: (params: PlanQuotaListParams) =>
    api.get<PlanQuotaListResponse>('/admin/plan-quota', { params }),
};
