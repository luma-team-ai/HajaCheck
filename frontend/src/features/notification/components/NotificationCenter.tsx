import { useMemo, useState } from 'react';
import type { NotificationItem } from '../../../shared/components/NotificationDropdown';
import { NotificationDropdown } from '../../../shared/components/NotificationDropdown';
import { NOTIFICATION_ALL_FILTER_KEY, NOTIFICATION_FILTERS, NOTIFICATION_TYPE_META } from '../constants';
import { useMarkNotificationsAsRead, useNotifications } from '../hooks/useNotifications';
import type { NotificationApiItem } from '../types';
import { formatElapsedTime } from '../utils/formatElapsedTime';

interface NotificationCenterProps {
  /** 패널 열림 여부 — Header 벨(shared, AppLayout 내부)이 AppShellRoute에 있어 토글 핸들러도
   * 그쪽이 소유한다. 이 컴포넌트는 activeFilter·목록 조회·읽음 처리만 책임진다. */
  open: boolean;
  onClose: () => void;
  /** 로그인 상태에서만 조회 — useAuthStore.user 유무를 그대로 전달 */
  enabled: boolean;
}

function extractDescription(payload: NotificationApiItem['payload']): string | undefined {
  const raw = payload?.description;
  return typeof raw === 'string' ? raw : undefined;
}

// Header 벨 클릭 시 열리는 알림 패널 컨테이너 — shared NotificationDropdown(프리젠테이션, 이은석 소유·
// 미터치)을 그대로 렌더한다(HAJA-38, Figma node-id 208-2458 / Anima 파트3 NotificationPanelSection).
export function NotificationCenter({ open, onClose, enabled }: NotificationCenterProps) {
  const [activeFilter, setActiveFilter] = useState(NOTIFICATION_ALL_FILTER_KEY);
  const { data } = useNotifications(enabled);
  const { markAsRead, markAllAsRead } = useMarkNotificationsAsRead();

  const notifications = useMemo(() => data ?? [], [data]);

  const items: NotificationItem[] = useMemo(
    () =>
      notifications.map((raw) => {
        const meta = NOTIFICATION_TYPE_META[raw.type];
        return {
          id: raw.id,
          category: meta.category,
          title: meta.title,
          description: extractDescription(raw.payload),
          timestamp: formatElapsedTime(raw.createdAt),
          read: raw.isRead,
          actionLabel: meta.actionLabel,
          onAction: () => markAsRead(raw.id),
        };
      }),
    [notifications, markAsRead],
  );

  const unreadCount = notifications.filter((item) => !item.isRead).length;

  // open 상태 자체는 여전히 이 컴포넌트가 소유(AppShellRoute는 boolean만 넘김) — 훅은 open과 무관하게
  // 항상 호출해 미리 데이터를 준비해 둔다(패널을 처음 열 때 로딩 깜빡임 최소화).
  if (!open) {
    return null;
  }

  return (
    <div className="fixed top-16 right-6 z-50 md:right-8">
      <NotificationDropdown
        notifications={items}
        unreadCount={unreadCount}
        filters={NOTIFICATION_FILTERS}
        activeFilter={activeFilter}
        onFilterChange={setActiveFilter}
        onMarkAllRead={() => markAllAsRead(notifications.filter((item) => !item.isRead).map((item) => item.id))}
        onViewAll={() => {
          // TODO(HAJA-38 후속): 알림 전체 보기 페이지(/notifications) 라우트가 생기면 navigate 연결
        }}
        onClose={onClose}
      />
    </div>
  );
}
