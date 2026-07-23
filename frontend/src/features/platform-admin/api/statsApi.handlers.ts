import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockServiceStats } from '../mocks/stats.mock';
import type { ServiceStatsResponse } from '../stats.types';

// 백엔드 /api/platform-admin/stats 미구현 구간의 MSW 핸들러(#634).
export const statsHandlers = [
  http.get('/api/platform-admin/stats', () => {
    const body: ApiResponse<ServiceStatsResponse> = { success: true, data: mockServiceStats };
    return HttpResponse.json(body);
  }),
];
