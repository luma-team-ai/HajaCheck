// 상담원 콘솔 공통 앱 셸(#1001, HAJA-495) — PlatformAdminShellRoute와 동일 패턴을 그대로 복제한다.
// 일반 사용자 셸(AppShellRoute)은 알림센터·마이페이지 프로필·챗봇 등 일반 사용자 세션에
// 강결합되어 있어 그대로 재사용하지 않고, AppLayout을 직접 조립하는 얇은 전용 래퍼로 둔다.
// router.tsx에서 CounselorRoute로 감싼 pathless 부모 route로 연결.
import { Outlet, useMatches } from 'react-router-dom';
import { useLogout } from '../features/auth/hooks/useLogout';
import { useAuthStore } from '../features/auth/store/authStore';
import { COUNSELOR_NAV_ITEM } from '../features/counsel/constants';
import type { BreadcrumbItem } from '../shared/components/Header';
import { AppLayout } from '../shared/components/AppLayout';
import { isRouteImplemented } from './implementedRoutes';

export interface CounselorShellHandle {
  breadcrumb: BreadcrumbItem[];
  activeHref?: string;
}

function hasCounselorShellHandle(handle: unknown): handle is CounselorShellHandle {
  return (
    typeof handle === 'object' &&
    handle !== null &&
    'breadcrumb' in handle &&
    Array.isArray((handle as { breadcrumb: unknown }).breadcrumb)
  );
}

export function CounselorShellRoute() {
  const matches = useMatches();
  const authUser = useAuthStore((state) => state.user);
  // 로그아웃 후에도 기업회원 /login으로 돌아가면 된다(COUNSELOR는 PLATFORM_ADMIN과 달리 전용
  // 로그인 화면이 없다) — useLogout 기본값(LOGIN_ROUTE) 그대로 사용.
  const { logout } = useLogout();
  const current = [...matches].reverse().find((match) => hasCounselorShellHandle(match.handle));
  const handle = current?.handle as CounselorShellHandle | undefined;

  return (
    <AppLayout
      breadcrumb={handle?.breadcrumb ?? []}
      activeHref={handle?.activeHref}
      isRouteImplemented={isRouteImplemented}
      // 일반 DEFAULT_ITEMS(대시보드/시설물 관리 등)는 이 콘솔과 무관하므로 비우고, 상담원 전용
      // 단일 메뉴("상담 대기열")만 노출한다. adminItem/isAdmin 슬롯(PlatformAdminShellRoute 패턴)을
      // 그대로 빌리면 SideNavBar가 로고 옆에 "ADMIN" 배지를 렌더하는데(SideNavBar.tsx 하드코딩),
      // 상담원은 관리자가 아니므로 오해를 부른다 — 그래서 adminItem이 아니라 일반 items로 전달한다.
      items={[COUNSELOR_NAV_ITEM]}
      brandHref="/counsel-console/queue"
      user={authUser ? { name: authUser.name } : undefined}
      onLogout={() => void logout()}
      profileMenu={
        authUser
          ? {
              name: authUser.name,
              email: authUser.email,
              onLogout: () => void logout(),
            }
          : undefined
      }
      // 우측 하단 고객지원 퀵상담 FAB — 상담원 본인이 상담을 처리하는 콘솔이라 고객 지원 진입점이
      // 불필요하다(PlatformAdminShellRoute와 동일 이유).
      showSupportFab={false}
    >
      <Outlet />
    </AppLayout>
  );
}
