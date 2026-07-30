import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockSystemMonitoring } from '../mocks/monitoring.mock';
import type { SystemMonitoringResponse } from '../monitoring.types';

// 백엔드 /api/platform-admin/monitoring 미구현 구간의 MSW 핸들러(#729).
export const monitoringHandlers = [
  http.get('/api/platform-admin/monitoring', () => {
    const body: ApiResponse<SystemMonitoringResponse> = { success: true, data: mockSystemMonitoring };
    return HttpResponse.json(body);
  }),
];
