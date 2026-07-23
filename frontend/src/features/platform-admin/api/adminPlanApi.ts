import { api } from '../../../shared/api/axios';
import type { AdminPlanCatalogResponse } from '../planQuota.types';

// 플랫폼 관리자 요금제 카탈로그 — "현재 플랜" 카드의 가격·한도는 이 응답(plans 테이블)에서 가져온다.
export const adminPlanApi = {
  getCatalog: () => api.get<AdminPlanCatalogResponse>('/platform-admin/plans'),
};
