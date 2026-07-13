import bellIcon from '../../../assets/brand/header-bell.svg';
import userIcon from '../../../assets/brand/header-user-outlined.svg';
import './Header.css';

export interface BreadcrumbItem {
  label: string;
  href?: string;
}

interface HeaderProps {
  breadcrumb: BreadcrumbItem[];
  unreadCount?: number;
  onNotificationClick?: () => void;
  onProfileClick?: () => void;
}

// Figma node-id 205-2333 "Header - Top Navigation (Pages Style)" — 로그인 후 내부 페이지
// 상단에 쓰는 헤더. 랜딩용 TopNavigation(node-id 63-2)과는 별개 컴포넌트(HAJA-149)
export function Header({ breadcrumb, unreadCount = 0, onNotificationClick, onProfileClick }: HeaderProps) {
  return (
    <header className="app-header">
      <nav className="app-header-breadcrumb" aria-label="현재 위치">
        {breadcrumb.map((item, index) => (
          <span key={item.label} className="app-header-breadcrumb-item">
            {index > 0 && <span className="app-header-breadcrumb-sep">{'>'}</span>}
            {item.href ? <a href={item.href}>{item.label}</a> : <span>{item.label}</span>}
          </span>
        ))}
      </nav>

      <div className="app-header-actions">
        <button
          type="button"
          className="app-header-notification"
          onClick={onNotificationClick}
          aria-label={unreadCount > 0 ? `알림 (미읽음 ${unreadCount}건)` : '알림'}
        >
          <img src={bellIcon} alt="" />
          {unreadCount > 0 && <span className="app-header-notification-dot" aria-hidden="true" />}
        </button>

        <button
          type="button"
          className="app-header-profile"
          onClick={onProfileClick}
          aria-label="내 프로필"
        >
          <img src={userIcon} alt="" />
        </button>
      </div>
    </header>
  );
}
