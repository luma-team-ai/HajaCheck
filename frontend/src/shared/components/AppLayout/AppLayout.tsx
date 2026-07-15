import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { Header } from '../Header';
import type { BreadcrumbItem } from '../Header';
import { SideNavBar } from '../SideNavBar';
import type { SideNavItem } from '../SideNavBar';
import { ChatbotButton } from './ChatbotButton';

interface AppLayoutUser {
  name: string;
  plan?: string;
  avatarUrl?: string;
}

interface AppLayoutProps {
  /** Header 브레드크럼(현재 위치) — 페이지별로 필수 지정 */
  breadcrumb: BreadcrumbItem[];
  children: ReactNode;
  /**
   * SideNavBar 활성 항목 경로. 미지정 시 현재 URL(useLocation) 기준.
   * 실제 라우트가 Figma 메뉴 href와 다른 페이지(예: /defects/:id → /defects/detail)는
   * 명시적으로 넘겨 해당 메뉴가 펼쳐/강조되도록 한다.
   */
  activeHref?: string;
  /** SideNavBar 메뉴 항목. 미지정 시 SideNavBar 기본 전체 메뉴(DEFAULT_ITEMS) 사용 */
  items?: SideNavItem[];
  /**
   * SideNavBar 메뉴 href가 실제로 이동 가능한 라우트인지 판별하는 함수. shared는 라우터 전체 구조를
   * 몰라야 하므로 호출부(app/AppShellRoute)가 주입 — 미지정 시 전부 구현된 것으로 간주(SideNavBar 기본값과 동일).
   */
  isRouteImplemented?: (href: string) => boolean;
  isAdmin?: boolean;
  /** 사이드바 하단 프로필. 미지정 시 프로필 블록 미표시 */
  user?: AppLayoutUser;
  /** 로그아웃 핸들러. 미지정 시 로그아웃 버튼 자체가 렌더링되지 않음 */
  onLogout?: () => void;
  /** Header 알림 미읽음 수 */
  unreadCount?: number;
  onNotificationClick?: () => void;
  onProfileClick?: () => void;
}

// 로그인 후 내부 페이지 공통 앱 셸 — 신규 공통 컴포넌트 SideNavBar + Header 조합(HAJA-186, #217).
// 구 DashboardLayout(Sidebar+TopBar)을 대체한다. shared는 feature(auth store 등)에 의존하지 않으므로
// 사용자/로그아웃/관리자 여부는 props 패스스루로 노출 — 호출하는 페이지/앱이 주입한다.
export function AppLayout({
  breadcrumb,
  children,
  activeHref,
  items,
  isRouteImplemented,
  isAdmin,
  user,
  onLogout,
  unreadCount,
  onNotificationClick,
  onProfileClick,
}: AppLayoutProps) {
  const location = useLocation();
  const resolvedActiveHref = activeHref ?? location.pathname;

  return (
    <div className="flex min-h-screen bg-white text-text-default">
      <SideNavBar
        items={items}
        activeHref={resolvedActiveHref}
        isRouteImplemented={isRouteImplemented}
        isAdmin={isAdmin}
        user={user}
        onLogout={onLogout}
      />
      <div className="flex min-w-0 flex-1 flex-col">
        <Header
          breadcrumb={breadcrumb}
          unreadCount={unreadCount}
          onNotificationClick={onNotificationClick}
          onProfileClick={onProfileClick}
        />
        <main className="min-w-0 flex-1 overflow-y-auto">{children}</main>
      </div>
      <ChatbotButton />
    </div>
  );
}
