import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockNotifications } from '../mocks/notification.mock';
import type { NotificationApiItem } from '../types';

export const notificationHandlers = [
  http.get('/api/notifications', () => {
    const body: ApiResponse<NotificationApiItem[]> = { success: true, data: mockNotifications };
    return HttpResponse.json(body);
  }),

  // 실 PATCH는 아직 BE 미배포일 수 있어(notificationApi.ts 주석) 목에서는 항상 성공으로 응답한다.
  http.patch('/api/notifications/:id/read', () => {
    const body: ApiResponse<null> = { success: true, data: null };
    return HttpResponse.json(body);
  }),
];
