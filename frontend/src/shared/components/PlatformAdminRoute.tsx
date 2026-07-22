import type { ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../../features/auth/store/authStore';
import { DASHBOARD_ROUTE, PLATFORM_ADMIN_LOGIN_ROUTE } from '../constants/routes';
import { isPlatformAdminRole } from '../constants/roles';

type Props = { children?: ReactNode };

// 플랫폼 관리자 콘솔 전용 가드(#535) — ProtectedRoute를 얇게 감싸는 AdminRoute와 달리 독립
// 컴포넌트로 둔다: 미인증 리다이렉트 대상이 ProtectedRoute의 내장 LOGIN_ROUTE('/login')가 아니라
// PLATFORM_ADMIN_LOGIN_ROUTE('/platform-admin/login')라 그대로 재사용할 수 없다.
// 역할 판정은 isPlatformAdminRole 하나로 통일 — PlatformAdminShellRoute(nav 노출)와 기준이 갈리면
// "메뉴는 보이는데 클릭하면 튕기는" 화면이 생긴다(roles.ts isAdminRole 주석과 동일 이유, #378).
export function PlatformAdminRoute({ children }: Props) {
  const user = useAuthStore((state) => state.user);

  if (!user) {
    return <Navigate to={PLATFORM_ADMIN_LOGIN_ROUTE} replace />;
  }

  // 권한 부족은 미인증과 다르게 다룬다 — 이미 로그인한(플랫폼 관리자가 아닌) 사용자를
  // 플랫폼 관리자 로그인 화면으로 다시 보내면 혼란스럽고 리다이렉트가 반복된다.
  // 일반 사용자 대시보드로 되돌린다(ProtectedRoute의 allowedRoles 불충족 처리와 동일 원칙).
  if (!isPlatformAdminRole(user.role)) {
    return <Navigate to={DASHBOARD_ROUTE} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
