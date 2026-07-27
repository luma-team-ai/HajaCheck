import type { ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../../features/auth/store/authStore';
import { DASHBOARD_ROUTE, LOGIN_ROUTE } from '../constants/routes';
import { isCounselorRole } from '../constants/roles';

type Props = { children?: ReactNode };

// 상담원 콘솔 전용 가드(#1001, HAJA-495) — PlatformAdminRoute와 동일 패턴이나, COUNSELOR는
// 별도 로그인 화면이 없어(기업회원 로그인 /login을 그대로 씀) 미인증 리다이렉트 대상도
// PLATFORM_ADMIN_LOGIN_ROUTE가 아니라 일반 LOGIN_ROUTE다.
// 역할 판정은 isCounselorRole 하나로 통일 — CounselorShellRoute(nav 노출)와 기준이 갈리면
// "메뉴는 보이는데 클릭하면 튕기는" 화면이 생긴다(roles.ts isAdminRole 주석과 동일 이유, #378).
export function CounselorRoute({ children }: Props) {
  const user = useAuthStore((state) => state.user);

  if (!user) {
    return <Navigate to={LOGIN_ROUTE} replace />;
  }

  // 권한 부족은 미인증과 다르게 다룬다 — 이미 로그인한(상담원이 아닌) 사용자를 로그인 화면으로
  // 다시 보내면 혼란스럽고 리다이렉트가 반복된다. 일반 사용자 대시보드로 되돌린다
  // (ProtectedRoute의 allowedRoles 불충족 처리·PlatformAdminRoute와 동일 원칙).
  if (!isCounselorRole(user.role)) {
    return <Navigate to={DASHBOARD_ROUTE} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
