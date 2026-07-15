export interface NotificationFilter {
  key: string;
  label: string;
}

export interface NotificationItem {
  id: string | number;
  category: string;
  title: string;
  description?: string;
  timestamp: string;
  read: boolean;
  actionLabel?: string;
  onAction?: () => void;
}

interface NotificationDropdownProps {
  notifications: NotificationItem[];
  unreadCount: number;
  filters?: NotificationFilter[];
  activeFilter?: string;
  onFilterChange?: (key: string) => void;
  onMarkAllRead?: () => void;
  onViewAll?: () => void;
}

// Figma node-id 208-2458 "Notification Dropdown" 기준 — 알림 유형별 아이콘 일러스트는
// 별도 아이콘 시스템이 필요해 이번 범위에서는 생략, unread dot으로만 상태 표시
export function NotificationDropdown({
  notifications,
  unreadCount,
  filters,
  activeFilter = 'all',
  onFilterChange,
  onMarkAllRead,
  onViewAll,
}: NotificationDropdownProps) {
  const visibleNotifications =
    !activeFilter || activeFilter === 'all'
      ? notifications
      : notifications.filter((item) => item.category === activeFilter);

  return (
    <div
      className="flex max-h-160 w-95 flex-col overflow-hidden rounded-2xl border border-border bg-white/90 shadow-2xl backdrop-blur-[10px]"
      role="menu"
      aria-label="알림"
    >
      <div className="flex items-end justify-between border-b border-neutral-100/50 px-5 pt-5 pb-[13px]">
        <div className="flex items-center gap-2">
          <h2 className="m-0 text-base font-semibold text-primary">알림</h2>
          <span className="text-sm text-text-muted">미읽음 {unreadCount}</span>
        </div>
        {onMarkAllRead && (
          <button
            type="button"
            className="cursor-pointer border-none bg-none p-0 text-sm text-text-muted"
            onClick={onMarkAllRead}
          >
            모두 읽음
          </button>
        )}
      </div>

      {filters && filters.length > 0 && (
        <div className="flex gap-2 overflow-x-auto border-b border-neutral-100/50 px-5 pt-3 pb-[13px]">
          {filters.map((filter) => (
            <button
              key={filter.key}
              type="button"
              className={`cursor-pointer rounded-full border px-[13px] py-[5px] text-xs whitespace-nowrap ${
                filter.key === activeFilter
                  ? 'border-primary bg-primary text-surface'
                  : 'border-border bg-none text-primary'
              }`}
              onClick={() => onFilterChange?.(filter.key)}
            >
              {filter.label}
            </button>
          ))}
        </div>
      )}

      <ul className="m-0 flex-1 list-none overflow-y-auto p-0">
        {visibleNotifications.length === 0 && (
          <li className="px-4 py-8 text-center text-[13px] text-text-muted">알림이 없습니다</li>
        )}
        {visibleNotifications.map((item) => (
          <li
            key={item.id}
            className={`relative flex gap-3 border-b border-neutral-100/30 px-4 pt-4 pb-[17px] ${
              item.read ? '' : 'bg-surface-muted/50'
            }`}
          >
            {!item.read && (
              <span className="absolute top-6 left-2 h-1.5 w-1.5 rounded-full bg-primary" aria-hidden="true" />
            )}
            <div className="flex min-w-0 flex-1 flex-col gap-1 pl-[14px]">
              <div className="flex items-start justify-between gap-2">
                <span className="text-sm font-medium text-primary">{item.title}</span>
                <span className="text-xs whitespace-nowrap text-text-muted">{item.timestamp}</span>
              </div>
              {item.description && (
                <p className="m-0 text-[13px] text-text-default">{item.description}</p>
              )}
              {item.actionLabel && (
                <button
                  type="button"
                  className="mt-1 self-start cursor-pointer rounded-full border border-border bg-surface px-[13px] py-[6px] text-xs text-primary"
                  onClick={item.onAction}
                >
                  {item.actionLabel}
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>

      {onViewAll && (
        <button
          type="button"
          className="w-full cursor-pointer border-none border-t border-neutral-100/50 bg-white/30 px-0 pt-[13px] pb-3 text-[13px] text-text-muted backdrop-blur-[2px]"
          onClick={onViewAll}
        >
          알림 전체 보기
        </button>
      )}
    </div>
  );
}
