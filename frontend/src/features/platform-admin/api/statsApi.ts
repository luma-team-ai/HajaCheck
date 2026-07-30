import { api } from '../../../shared/api/axios';
import type { ServiceStatsResponse } from '../stats.types';

// 플랫폼 관리자 서비스 통계 API — 회사 스코프 없이 전사 데이터를 다룬다(#634, 백엔드 별도 구현).
// 계약 확정 시 경로만 여기서 한 번 맞추면 된다(planQuotaApi.ts와 동일 전략).
export const statsApi = {
  getStats: () => api.get<ServiceStatsResponse>('/platform-admin/stats'),
};
