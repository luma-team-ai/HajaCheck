import { api } from '../../../shared/api/axios';
import type { NotificationApiItem } from '../types';

export const notificationApi = {
  getList: () => api.get<NotificationApiItem[]>('/notifications'),
  // NotificationController.java 주석: 읽음처리 PATCH는 이벤트 발행 트리거와 함께 별도 PR(HAJA-274)
  // 범위라 아직 BE 미배포일 수 있다 — 호출부(useNotifications.ts)가 실패를 삼킨다.
  markAsRead: (id: number) => api.patch<null>(`/notifications/${id}/read`),
};
