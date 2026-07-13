import './NotificationDropdown.css';

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
    <div className="notification-dropdown" role="menu" aria-label="알림">
      <div className="notification-dropdown-header">
        <div className="notification-dropdown-title">
          <h2>알림</h2>
          <span className="notification-dropdown-unread">미읽음 {unreadCount}</span>
        </div>
        {onMarkAllRead && (
          <button type="button" className="notification-dropdown-mark-all" onClick={onMarkAllRead}>
            모두 읽음
          </button>
        )}
      </div>

      {filters && filters.length > 0 && (
        <div className="notification-dropdown-filters">
          {filters.map((filter) => (
            <button
              key={filter.key}
              type="button"
              className={`notification-dropdown-filter${
                filter.key === activeFilter ? ' notification-dropdown-filter--active' : ''
              }`}
              onClick={() => onFilterChange?.(filter.key)}
            >
              {filter.label}
            </button>
          ))}
        </div>
      )}

      <ul className="notification-dropdown-list">
        {visibleNotifications.length === 0 && (
          <li className="notification-dropdown-empty">알림이 없습니다</li>
        )}
        {visibleNotifications.map((item) => (
          <li
            key={item.id}
            className={`notification-dropdown-row${item.read ? '' : ' notification-dropdown-row--unread'}`}
          >
            {!item.read && <span className="notification-dropdown-dot" aria-hidden="true" />}
            <div className="notification-dropdown-row-body">
              <div className="notification-dropdown-row-top">
                <span className="notification-dropdown-row-title">{item.title}</span>
                <span className="notification-dropdown-row-time">{item.timestamp}</span>
              </div>
              {item.description && (
                <p className="notification-dropdown-row-desc">{item.description}</p>
              )}
              {item.actionLabel && (
                <button
                  type="button"
                  className="notification-dropdown-row-action"
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
        <button type="button" className="notification-dropdown-footer" onClick={onViewAll}>
          알림 전체 보기
        </button>
      )}
    </div>
  );
}
