import { api } from '../../../shared/api/axios';
import type { AdminCurrentPlanResponse, AdminPlanCatalogResponse } from '../planQuota.types';

export const adminPlanApi = {
  // 관리자 요금제 카탈로그 — "현재 플랜" 카드의 가격·한도는 이 응답(plans 테이블)에서 가져온다.
  getCatalog: () => api.get<AdminPlanCatalogResponse>('/admin/plans'),
  // 내 회사의 현재 구독+이번 달 사용량 — "사용자 등록" 버튼 클릭 시 좌석 잔여 확인(#872 후속)에 쓴다.
  getCurrentPlan: () => api.get<AdminCurrentPlanResponse>('/admin/plan'),
};
