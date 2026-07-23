import { api } from '../../../shared/api/axios';
import type { PlanPolicyApiItem } from '../planPolicy.mapper';

interface PlanPolicyCatalogResponse {
  plans: PlanPolicyApiItem[];
}

// 플랫폼 관리자 "플랜 정책 설정" — plans 테이블 조회·일괄 변경(#624 후속, 사용자 지시).
// PUT은 3개 플랜(FREE/STANDARD/ENTERPRISE)을 한 번에 원자적으로 교체한다(백엔드 계약).
export const planPolicyApi = {
  getCatalog: () => api.get<PlanPolicyCatalogResponse>('/platform-admin/plans'),
  updateCatalog: (plans: PlanPolicyApiItem[]) =>
    api.put<PlanPolicyCatalogResponse>('/platform-admin/plans', { plans }),
};
