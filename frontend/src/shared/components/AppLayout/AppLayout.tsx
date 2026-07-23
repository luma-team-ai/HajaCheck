import type { ReactNode, MouseEvent as ReactMouseEvent } from 'react';
import { useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Header } from '../Header';
import type { BreadcrumbItem } from '../Header';
import { SideNavBar } from '../SideNavBar';
import type { SideNavItem } from '../SideNavBar';
import { BottomNavBarFab } from '../BottomNavBarFab';
import { FloatingPopup } from '../FloatingPopup';

// 고객지원 퀵링크 패널 진입점 — 아직 실제로 구현된 지원 페이지는 AI 어시스턴트(/support/ai-assistant)
// 뿐이라(상담 챗봇·상담 이력은 미구현 placeholder), 모든 항목이 그 페이지로 이동한다(#499, 사용자 결정).
const SUPPORT_ENTRY_HREF = '/support/ai-assistant';

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
  /** SideNavBar isAdmin=true일 때 items에 덧붙는 관리자 그룹. 미지정 시 SideNavBar 기본값(DEFAULT_ADMIN_ITEM,
   * 기업 관리자 콘솔) — 플랫폼 관리자 콘솔(#535)은 이 값을 override해 별도 그룹을 노출한다. */
  adminItem?: SideNavItem;
  /**
   * SideNavBar 메뉴 href가 실제로 이동 가능한 라우트인지 판별하는 함수. shared는 라우터 전체 구조를
   * 몰라야 하므로 호출부(app/AppShellRoute)가 주입 — 미지정 시 전부 구현된 것으로 간주(SideNavBar 기본값과 동일).
   */
  isRouteImplemented?: (href: string) => boolean;
  isAdmin?: boolean;
  /** SideNavBar 브랜드 로고 링크 override(#535 플랫폼 관리자 콘솔). 미지정 시 SideNavBar 기본값('/dashboard') */
  brandHref?: string;
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
  adminItem,
  isRouteImplemented,
  isAdmin,
  brandHref,
  user,
  onLogout,
  unreadCount,
  onNotificationClick,
  onProfileClick,
}: AppLayoutProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const resolvedActiveHref = activeHref ?? location.pathname;
  const [isSupportPopupOpen, setIsSupportPopupOpen] = useState(false);
  // 퀵상담 FAB 재클릭 토글 경합(PR머신 P2, 이슈 #546) 가드 — FloatingPopup은 useOutsideDismiss로
  // document mousedown에서 바깥 클릭을 감지해 onClose를 부른다. FAB는 그 컨테이너 바깥이라, 팝업이
  // 열린 상태에서 FAB를 다시 클릭하면 실제 이벤트 순서(mousedown→click)상 mousedown이 먼저 팝업을
  // 닫고, 뒤이은 click이 다시 토글해 재오픈해버린다. AppShellRoute.tsx의 벨 버튼과 동일한 패턴
  // (suppressNextBellClickRef, PR머신 P2 #474)으로 해소한다.
  const suppressNextFabClickRef = useRef(false);

  function goToSupportEntry() {
    setIsSupportPopupOpen(false);
    navigate(SUPPORT_ENTRY_HREF);
  }

  function handleFabClick() {
    if (suppressNextFabClickRef.current) {
      suppressNextFabClickRef.current = false;
      return;
    }
    setIsSupportPopupOpen((open) => !open);
  }

  // 패널이 닫혀 있으면 무조건 false로 덮어써(벨 버튼 가드와 동일 이유 — 우클릭·드래그아웃처럼
  // click이 뒤따르지 않는 mousedown 이후에도 플래그가 true로 고정돼 다음 정상 클릭을 삼키지 않게 함)
  function handleShellMouseDownCapture(event: ReactMouseEvent<HTMLDivElement>) {
    if (!isSupportPopupOpen) {
      suppressNextFabClickRef.current = false;
      return;
    }
    const target = event.target as Element | null;
    suppressNextFabClickRef.current = Boolean(target?.closest('button[aria-label^="고객지원 챗봇"]'));
  }

  return (
    <div
      className="flex min-h-screen bg-white text-text-default"
      onMouseDownCapture={handleShellMouseDownCapture}
    >
      <SideNavBar
        items={items}
        adminItem={adminItem}
        activeHref={resolvedActiveHref}
        isRouteImplemented={isRouteImplemented}
        isAdmin={isAdmin}
        brandHref={brandHref}
        // 사이드바 하단 프로필은 관리자에게만 노출 — 일반 사용자 프로필은 별도 이슈에서 헤더에 붙일 예정(HAJA-167, #184)
        user={isAdmin ? user : undefined}
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
      <BottomNavBarFab onClick={handleFabClick} />
      {isSupportPopupOpen && (
        <FloatingPopup
          onClose={() => setIsSupportPopupOpen(false)}
          links={[
            { label: '서비스 이용 방법', onClick: goToSupportEntry },
            { label: '분석 결과 문의', onClick: goToSupportEntry },
            { label: '요금·기타', onClick: goToSupportEntry },
          ]}
          onConnectAgent={goToSupportEntry}
        />
      )}
    </div>
  );
}
