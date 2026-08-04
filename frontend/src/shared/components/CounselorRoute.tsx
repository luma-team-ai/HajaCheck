import type { ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../../features/auth/store/authStore';
import { COUNSELOR_LOGIN_ROUTE, resolveRoleHomeRoute } from '../constants/routes';
import { isCounselorRole } from '../constants/roles';

type Props = { children?: ReactNode };

// 상담원 콘솔 전용 가드(#1001, HAJA-495) — PlatformAdminRoute와 동일 패턴. COUNSELOR 전용
// 로그인 화면(CounselorLoginPage)이 신설되어 미인증 리다이렉트 대상도 일반 LOGIN_ROUTE가 아니라
// PlatformAdminRoute와 동일하게 전용 로그인 경로(COUNSELOR_LOGIN_ROUTE)로 보낸다.
// 역할 판정은 isCounselorRole 하나로 통일 — CounselorShellRoute(nav 노출)와 기준이 갈리면
// "메뉴는 보이는데 클릭하면 튕기는" 화면이 생긴다(roles.ts isAdminRole 주석과 동일 이유, #378).
export function CounselorRoute({ children }: Props) {
  const user = useAuthStore((state) => state.user);

  if (!user) {
    return <Navigate to={COUNSELOR_LOGIN_ROUTE} replace />;
  }

  // 권한 부족은 미인증과 다르게 다룬다 — 이미 로그인한(상담원이 아닌) 사용자를 로그인 화면으로
  // 다시 보내면 혼란스럽고 리다이렉트가 반복된다. 각 role의 홈으로 되돌린다
  // (ProtectedRoute의 allowedRoles 불충족 처리·PlatformAdminRoute와 동일 원칙).
  //
  // DASHBOARD_ROUTE 고정이 아닌 이유(#1513): 대시보드(AppShell)에 allowedRoles가 걸린 뒤로는
  // PLATFORM_ADMIN을 대시보드로 보내면 그 가드가 다시 튕겨 플랫폼 콘솔로 보낸다 — 결과는 같아도
  // 2홉을 돈다. resolveRoleHomeRoute(단일 진실 소스)로 한 번에 정착시킨다.
  if (!isCounselorRole(user.role)) {
    return <Navigate to={resolveRoleHomeRoute(user.role)} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
