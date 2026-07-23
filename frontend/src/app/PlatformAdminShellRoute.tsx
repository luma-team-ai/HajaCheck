// 플랫폼 관리자 콘솔 공통 앱 셸(#535) — 일반 사용자 셸(AppShellRoute)은 알림센터·마이페이지
// 프로필·챗봇 등 일반 사용자 세션에 강결합되어 있어 그대로 재사용하지 않고, AppLayout을 직접
// 조립하는 얇은 전용 래퍼로 둔다. router.tsx에서 PlatformAdminRoute로 감싼 pathless 부모 route로 연결.
import { Outlet, useMatches } from 'react-router-dom';
import { useLogout } from '../features/auth/hooks/useLogout';
import { useAuthStore } from '../features/auth/store/authStore';
import { PLATFORM_ADMIN_NAV_ITEM } from '../features/platform-admin/constants';
import type { BreadcrumbItem } from '../shared/components/Header';
import { AppLayout } from '../shared/components/AppLayout';
import { PLATFORM_ADMIN_LOGIN_ROUTE } from '../shared/constants/routes';
import { isRouteImplemented } from './implementedRoutes';

export interface PlatformAdminShellHandle {
  breadcrumb: BreadcrumbItem[];
  activeHref?: string;
}

function hasPlatformAdminShellHandle(handle: unknown): handle is PlatformAdminShellHandle {
  return (
    typeof handle === 'object' &&
    handle !== null &&
    'breadcrumb' in handle &&
    Array.isArray((handle as { breadcrumb: unknown }).breadcrumb)
  );
}

export function PlatformAdminShellRoute() {
  const matches = useMatches();
  const authUser = useAuthStore((state) => state.user);
  // 로그아웃 후 기업회원 /login이 아니라 /platform-admin/login으로 돌아가야 한다(useLogout redirectTo, #535).
  const { logout } = useLogout(PLATFORM_ADMIN_LOGIN_ROUTE);
  const current = [...matches].reverse().find((match) => hasPlatformAdminShellHandle(match.handle));
  const handle = current?.handle as PlatformAdminShellHandle | undefined;

  return (
    <AppLayout
      breadcrumb={handle?.breadcrumb ?? []}
      activeHref={handle?.activeHref}
      isRouteImplemented={isRouteImplemented}
      // 일반 DEFAULT_ITEMS(대시보드/시설물 관리 등)는 이 콘솔과 무관하므로 비우고,
      // 플랫폼 관리자 7개 메뉴 그룹만 노출한다(SideNavBar: isAdmin=true → [...items, adminItem]).
      items={[]}
      adminItem={PLATFORM_ADMIN_NAV_ITEM}
      isAdmin
      // 로고 클릭 시 "사용자 관리"로 바로 이동 + 그 메뉴가 활성 선택되도록, 리다이렉트를 거치는
      // 인덱스 경로('/platform-admin')가 아니라 첫 메뉴 경로를 직접 가리킨다(router.tsx의
      // '/platform-admin/users' 라우트 handle.activeHref와 동일 값이어야 사이드바가 강조된다).
      brandHref="/platform-admin/users"
      user={authUser ? { name: authUser.name } : undefined}
      onLogout={() => void logout()}
      // 우측 하단 고객지원 퀵상담 FAB — 이 콘솔은 플랫폼 운영진 전용이라 고객 지원 진입점이 불필요(사용자 지시).
      showSupportFab={false}
    >
      <Outlet />
    </AppLayout>
  );
}
