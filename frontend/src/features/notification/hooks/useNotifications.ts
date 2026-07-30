import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationApi } from '../api/notificationApi';
import type { NotificationApiItem } from '../types';

export const notificationKeys = {
  list: ['notification', 'list'] as const,
};

// 로그인 상태에서만 조회 — 호출부(NotificationCenter/AppShellRoute)가 useAuthStore.user 유무를
// enabled로 넘긴다. 미인증이면 query 자체가 fetch하지 않으므로 벨 unreadCount는 0으로 남는다.
export function useNotifications(enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.list,
    queryFn: () => notificationApi.getList().then((res) => res.data),
    enabled,
  });
}

// 개별/전체 읽음 처리. 실 PATCH(/api/notifications/{id}/read)는 알림 생성 트리거와 함께 별도
// PR(HAJA-274) 범위라 아직 BE에 배포되지 않았을 수 있다 — 캐시는 낙관적으로 먼저 갱신해 화면(모두 읽음
// 클릭 시 unreadCount 0)이 즉시 반영되게 하고, 실 요청 중 하나라도 실패하면 onError에서 그 낙관적
// 갱신을 원복한다(Promise.all이라 하나라도 reject되면 mutation 자체가 reject되어 onError가 호출된다 —
// 이전에 Promise.allSettled를 쓰면 항상 fulfilled라 onError 롤백이 데드코드였다, react-reviewer P1).
// 별도 에러 토스트는 띄우지 않는다(재시도 UX는 PATCH 배포 후 후속 이슈).
export function useMarkNotificationsAsRead() {
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: (ids: number[]) => Promise.all(ids.map((id) => notificationApi.markAsRead(id))),
    onMutate: async (ids: number[]) => {
      await queryClient.cancelQueries({ queryKey: notificationKeys.list });
      const previous = queryClient.getQueryData<NotificationApiItem[]>(notificationKeys.list);
      queryClient.setQueryData<NotificationApiItem[]>(notificationKeys.list, (current) =>
        current?.map((item) => (ids.includes(item.id) ? { ...item, isRead: true } : item)),
      );
      return { previous };
    },
    onError: (_error, _ids, context) => {
      if (context?.previous) {
        queryClient.setQueryData(notificationKeys.list, context.previous);
      }
    },
  });

  return {
    markAsRead: (id: number) => mutation.mutate([id]),
    markAllAsRead: (ids: number[]) => mutation.mutate(ids),
    isPending: mutation.isPending,
  };
}

// 개별 닫기(X) — DELETE /api/notifications/{id}로 서버에서 실제 삭제한다. 목록에서 즉시 사라지도록
// 캐시를 낙관적으로 먼저 제거하고, 실패하면 원복한다(읽음처리와 동일한 패턴). 성공 시 목록을
// invalidate해 서버 상태(30건 컷에 밀려 있던 다음 알림 등)와 다시 맞춘다.
export function useDeleteNotification() {
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: (id: number) => notificationApi.remove(id),
    onMutate: async (id: number) => {
      await queryClient.cancelQueries({ queryKey: notificationKeys.list });
      const previous = queryClient.getQueryData<NotificationApiItem[]>(notificationKeys.list);
      queryClient.setQueryData<NotificationApiItem[]>(notificationKeys.list, (current) =>
        current?.filter((item) => item.id !== id),
      );
      return { previous };
    },
    onError: (_error, _id, context) => {
      if (context?.previous) {
        queryClient.setQueryData(notificationKeys.list, context.previous);
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: notificationKeys.list });
    },
  });

  return {
    deleteNotification: (id: number) => mutation.mutate(id),
    isPending: mutation.isPending,
  };
}
