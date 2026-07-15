import { useEffect, useRef, useState } from 'react';
import { useLogout } from '../../../features/auth/hooks/useLogout';
import { useAuthStore } from '../../../features/auth/store/authStore';

type Props = {
  // 상단바 breadcrumb 현재 위치 표시 — 페이지별로 다름(HAJA-185, 마이페이지 재사용 대비).
  // 미지정 시 기존 대시보드 동작 그대로 유지(하위 호환).
  currentLabel?: string;
};

export function TopBar({ currentLabel = '대시보드' }: Props) {
  const user = useAuthStore((state) => state.user);
  const { logout } = useLogout();
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // 아바타 메뉴 바깥 클릭 시 닫기 — NotificationDropdown과 동일한 패턴(document mousedown)
  useEffect(() => {
    if (!isMenuOpen) return undefined;

    function handlePointerDown(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setIsMenuOpen(false);
      }
    }

    document.addEventListener('mousedown', handlePointerDown);
    return () => document.removeEventListener('mousedown', handlePointerDown);
  }, [isMenuOpen]);

  const handleAvatarClick = () => {
    if (!user) return;
    setIsMenuOpen((prev) => !prev);
  };

  const handleLogoutClick = () => {
    setIsMenuOpen(false);
    void logout();
  };

  return (
    <header className="topbar">
      <nav className="topbar-breadcrumb" aria-label="현재 위치">
        <span>홈</span>
        <span className="topbar-breadcrumb-sep">/</span>
        <span className="topbar-breadcrumb-current">{currentLabel}</span>
      </nav>

      <div className="topbar-actions">
        <button type="button" className="topbar-icon-btn" aria-label="알림">
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
            <path d="M13.73 21a2 2 0 0 1-3.46 0" />
          </svg>
        </button>

        <div className="topbar-profile" ref={menuRef}>
          <button
            type="button"
            className="topbar-avatar"
            aria-label={user ? `${user.name} 프로필 메뉴` : '내 프로필'}
            aria-haspopup="menu"
            aria-expanded={isMenuOpen}
            onClick={handleAvatarClick}
          >
            {user?.profileImageUrl ? (
              <img src={user.profileImageUrl} alt="" className="topbar-avatar-img" />
            ) : user ? (
              <span className="topbar-avatar-initial" aria-hidden="true">
                {user.name.charAt(0)}
              </span>
            ) : (
              <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0 2c-4.42 0-8 2.24-8 5v1h16v-1c0-2.76-3.58-5-8-5Z" />
              </svg>
            )}
          </button>

          {isMenuOpen && user && (
            <div className="topbar-profile-menu" role="menu">
              <div className="topbar-profile-info">
                <span className="topbar-profile-name">{user.name}</span>
                <span className="topbar-profile-email">{user.email}</span>
              </div>
              <button
                type="button"
                role="menuitem"
                className="topbar-profile-logout"
                onClick={handleLogoutClick}
              >
                로그아웃
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
