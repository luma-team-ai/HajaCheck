import { useState } from 'react';
import { Link } from 'react-router-dom';
import bellIcon from '../../../assets/brand/header-bell.svg';
import userIcon from '../../../assets/brand/header-user-outlined.svg';
import { ProfileMenu } from '../ProfileMenu';
import type { ProfileMenuProps } from '../ProfileMenu';

export interface BreadcrumbItem {
  label: string;
  href?: string;
}

// ProfileMenu 콘텐츠·액션 — onClose는 Header가 드롭다운 열림 상태를 직접 소유하므로 여기서 받지 않는다
export type HeaderProfileMenu = Omit<ProfileMenuProps, 'onClose'>;

interface HeaderProps {
  breadcrumb: BreadcrumbItem[];
  unreadCount?: number;
  onNotificationClick?: () => void;
  /** profileMenu 미지정 시 프로필 버튼 클릭에 대한 기존 동작(예: 페이지 이동) */
  onProfileClick?: () => void;
  /** 제공 시 프로필 버튼 클릭이 onProfileClick 대신 이 정보로 드롭다운 메뉴를 연다(HAJA-758) */
  profileMenu?: HeaderProfileMenu;
}

// Figma node-id 205-2333 "Header - Top Navigation (Pages Style)" — 로그인 후 내부 페이지
// 상단에 쓰는 헤더. 랜딩용 TopNavigation(node-id 63-2)과는 별개 컴포넌트(HAJA-149)
export function Header({ breadcrumb, unreadCount = 0, onNotificationClick, onProfileClick, profileMenu }: HeaderProps) {
  const [isProfileMenuOpen, setProfileMenuOpen] = useState(false);

  function handleProfileButtonClick() {
    if (profileMenu) {
      setProfileMenuOpen((open) => !open);
      return;
    }
    onProfileClick?.();
  }

  function closeProfileMenu() {
    setProfileMenuOpen(false);
  }

  return (
    <header className="relative flex h-16 items-center justify-between bg-white/90 px-8 shadow-[inset_0px_1px_0px_0px_#fff] backdrop-blur-[10px]">
      <nav className="flex items-center gap-1.5 text-base text-text-default" aria-label="현재 위치">
        {breadcrumb.map((item, index) => (
          <span key={item.label} className="inline-flex items-center gap-1.5">
            {index > 0 && <span className="text-text-muted">{'>'}</span>}
            {item.href ? (
              <Link to={item.href} className="text-inherit no-underline">
                {item.label}
              </Link>
            ) : (
              <span>{item.label}</span>
            )}
          </span>
        ))}
      </nav>

      <div className="flex items-center gap-3">
        <button
          type="button"
          className="relative inline-flex h-10 w-10 cursor-pointer items-center justify-center rounded-full border-none bg-none hover:bg-surface-muted"
          onClick={onNotificationClick}
          aria-label={unreadCount > 0 ? `알림 (미읽음 ${unreadCount}건)` : '알림'}
        >
          <img className="h-5 w-4" src={bellIcon} alt="" />
          {unreadCount > 0 && (
            <span
              className="absolute top-2 right-[9px] h-1.5 w-1.5 rounded-full bg-danger"
              aria-hidden="true"
            />
          )}
        </button>

        <div className="relative">
          <button
            type="button"
            className="inline-flex h-8 w-8 cursor-pointer items-center justify-center rounded-full border border-border bg-[#ece6ee] p-0"
            onClick={handleProfileButtonClick}
            aria-label="내 프로필"
            aria-haspopup={profileMenu ? 'menu' : undefined}
            aria-expanded={profileMenu ? isProfileMenuOpen : undefined}
          >
            <img className="h-5 w-5" src={userIcon} alt="" />
          </button>

          {profileMenu && isProfileMenuOpen && (
            <div className="absolute top-full right-0 z-50 mt-2">
              <ProfileMenu
                {...profileMenu}
                onMyInfoClick={() => {
                  closeProfileMenu();
                  profileMenu.onMyInfoClick();
                }}
                onMyPlanClick={() => {
                  closeProfileMenu();
                  profileMenu.onMyPlanClick();
                }}
                onLogout={() => {
                  closeProfileMenu();
                  profileMenu.onLogout();
                }}
                onClose={closeProfileMenu}
              />
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
