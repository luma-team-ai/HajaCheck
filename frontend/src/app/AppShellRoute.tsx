// 로그인 후 내부 페이지 공통 앱 셸(AppLayout) 라우트 래퍼 — router.tsx의 pathless 부모 route로 연결(HAJA-186, #217 후속)
// 목적: AppLayout 연결을 페이지 작성자의 자발적 opt-in이 아니라 라우터 레벨에서 강제한다.
// 새 페이지를 이 셸에 포함하려면 router.tsx의 children 배열에 라우트를 추가하고,
// 그 라우트의 `handle`에 breadcrumb/activeHref를 선언하기만 하면 된다 — 페이지 컴포넌트 자체는
// AppLayout을 몰라도 됨(react-router v6 표준 패턴: useMatches() + handle).
import { useState } from 'react';
import { Outlet, useMatches, useNavigate } from 'react-router-dom';
import { useLogout } from '../features/auth/hooks/useLogout';
import { MYPAGE_PLAN_ROUTE } from '../features/auth/constants';
import { useAuthStore } from '../features/auth/store/authStore';
import { NotificationCenter } from '../features/notification/components/NotificationCenter';
import { useNotifications } from '../features/notification/hooks/useNotifications';
import type { BreadcrumbItem } from '../shared/components/Header';
import { AppLayout } from '../shared/components/AppLayout';
import { isAdminRole } from '../shared/constants/roles';
import { isRouteImplemented } from './implementedRoutes';

export interface AppShellHandle {
  /** Header 브레드크럼(현재 위치) */
  breadcrumb: BreadcrumbItem[];
  /**
   * SideNavBar 활성 항목 경로. 미지정 시 AppLayout이 현재 URL(useLocation) 기준으로 계산.
   * 실제 라우트가 SideNavBar 메뉴 href와 다른 페이지(예: /defects/:id → /defects/detail)는
   * 명시적으로 지정해 해당 메뉴가 강조되도록 한다.
   */
  activeHref?: string;
}

function hasAppShellHandle(handle: unknown): handle is AppShellHandle {
  return (
    typeof handle === 'object' &&
    handle !== null &&
    'breadcrumb' in handle &&
    Array.isArray((handle as { breadcrumb: unknown }).breadcrumb)
  );
}

export function AppShellRoute() {
  const matches = useMatches();
  const navigate = useNavigate();
  const authUser = useAuthStore((state) => state.user);
  // 관리자 메뉴/사이드바 프로필 노출 여부 — role 기반(HAJA-167, #184).
  // AppLayout이 isAdmin일 때만 SideNavBar에 user를 전달하도록 내부에서 필터링한다.
  // AdminRoute(shared/components/AdminRoute.tsx)의 실제 접근 차단과 같은 기준(isAdminRole)을 쓴다
  // — 각자 role === 'ADMIN'을 따로 비교하면 한쪽만 바뀌었을 때 메뉴·접근 판정이 어긋난다(#378).
  const isAdmin = isAdminRole(authUser?.role);
  const { logout } = useLogout();
  // 가장 깊은(마지막) match부터 breadcrumb/activeHref를 선언한 handle을 찾는다.
  const current = [...matches].reverse().find((match) => hasAppShellHandle(match.handle));
  const handle = current?.handle as AppShellHandle | undefined;

  // 알림 센터(HAJA-38) — Header 벨 버튼은 AppLayout 내부(shared, 미터치)라 열림 상태와 unreadCount는
  // 이 통합지점(app/)이 들고 NotificationCenter(컨테이너)에 boolean으로만 내려준다.
  // useNotifications는 NotificationCenter 안에서도 같은 쿼리 키로 호출되므로 TanStack Query 캐시가
  // 공유되어 벨 배지용으로 별도 네트워크 요청이 추가되지는 않는다.
  const isAuthenticated = Boolean(authUser);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const { data: notifications } = useNotifications(isAuthenticated);
  const unreadCount = notifications?.filter((item) => !item.isRead).length ?? 0;

  return (
    <>
      <AppLayout
        breadcrumb={handle?.breadcrumb ?? []}
        activeHref={handle?.activeHref}
        isRouteImplemented={isRouteImplemented}
        isAdmin={isAdmin}
        user={
          authUser
            ? { name: authUser.name, avatarUrl: authUser.profileImageUrl ?? undefined }
            : undefined
        }
        onLogout={() => void logout()}
        onProfileClick={() => navigate(MYPAGE_PLAN_ROUTE)}
        unreadCount={unreadCount}
        onNotificationClick={() => setNotificationOpen((prev) => !prev)}
      >
        <Outlet />
      </AppLayout>
      <NotificationCenter
        open={notificationOpen}
        onClose={() => setNotificationOpen(false)}
        enabled={isAuthenticated}
      />
    </>
  );
}
