import { api } from '../../../shared/api/axios';
import type { SystemMonitoringResponse } from '../monitoring.types';

// 플랫폼 관리자 시스템 모니터링 API — 회사 스코프 없이 전사 인프라 상태를 다룬다(#729, 백엔드 별도 구현).
// 계약 확정 시 경로만 여기서 한 번 맞추면 된다(statsApi.ts와 동일 전략).
export const monitoringApi = {
  getMonitoring: () => api.get<SystemMonitoringResponse>('/platform-admin/monitoring'),
};
