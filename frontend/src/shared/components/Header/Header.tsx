import { useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
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
  // 프로필 버튼 재클릭 토글 경합(벨 버튼 suppressNextBellClickRef #474, FAB suppressNextFabClickRef #546과
  // 동일 패턴) 가드 — ProfileMenu는 useOutsideDismiss로 document mousedown에서 바깥 클릭을 감지해
  // onClose를 부르는데, 프로필 버튼은 그 rootRef 바깥이라 드롭다운이 열린 상태에서 버튼을 다시 클릭하면
  // 실제 이벤트 순서(mousedown→click)상 mousedown이 먼저 드롭다운을 닫고, 뒤이은 click이 다시 토글해
  // 재오픈해버린다. 패널이 닫혀 있으면 무조건 false로 덮어써(우클릭·드래그아웃처럼 click이 뒤따르지
  // 않는 mousedown 이후에도 플래그가 true로 고정돼 다음 정상 클릭을 삼키지 않게 함).
  const suppressNextProfileClickRef = useRef(false);

  function handleProfileWrapperMouseDownCapture(event: ReactMouseEvent<HTMLDivElement>) {
    if (!isProfileMenuOpen) {
      suppressNextProfileClickRef.current = false;
      return;
    }
    const target = event.target as Element | null;
    suppressNextProfileClickRef.current = Boolean(target?.closest('button[aria-label="내 프로필"]'));
  }

  function handleProfileButtonClick() {
    if (suppressNextProfileClickRef.current) {
      suppressNextProfileClickRef.current = false;
      return;
    }
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

        <div className="relative" onMouseDownCapture={handleProfileWrapperMouseDownCapture}>
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
            // absolute+top-full은 헤더의 로컬 스태킹 컨텍스트에 갇혀, 지도뷰(카카오맵이 CustomOverlay에
            // 큰 zIndex를 직접 지정 — features/map/MapPage.tsx 참조)처럼 명시적으로 높은 z-index를 쓰는
            // 페이지 콘텐츠에 가려질 수 있었다(다른 담당자 리포트). 알림 드롭다운(NotificationCenter.tsx)이
            // 이미 이 문제를 겪지 않는 이유는 fixed로 뷰포트 기준 최상위 스태킹 컨텍스트에 렌더되기
            // 때문 — 동일 패턴으로 맞춘다. 헤더 우측 끝(px-8) 버튼이라 오프셋도 그와 동일하게 right-8.
            <div className="fixed top-16 right-8 z-50">
              <ProfileMenu
                {...profileMenu}
                onMyInfoClick={
                  profileMenu.onMyInfoClick
                    ? () => {
                        closeProfileMenu();
                        profileMenu.onMyInfoClick?.();
                      }
                    : undefined
                }
                onMyPlanClick={
                  profileMenu.onMyPlanClick
                    ? () => {
                        closeProfileMenu();
                        profileMenu.onMyPlanClick?.();
                      }
                    : undefined
                }
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
