import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockMyPlan, mockSeats } from '../mocks/mypage.mock';
import type { MyPlan, SeatsInfo, UpgradeInquiryResult } from '../types';

export const mypageHandlers = [
  http.get('/api/me/plan', () => {
    const body: ApiResponse<MyPlan> = { success: true, data: mockMyPlan };
    return HttpResponse.json(body);
  }),

  http.get('/api/me/seats', () => {
    const body: ApiResponse<SeatsInfo> = { success: true, data: mockSeats };
    return HttpResponse.json(body);
  }),

  http.post('/api/me/plan/upgrade-inquiry', () => {
    const body: ApiResponse<UpgradeInquiryResult> = {
      success: true,
      data: { status: 'UPGRADE_REQUESTED' },
    };
    return HttpResponse.json(body);
  }),
];
