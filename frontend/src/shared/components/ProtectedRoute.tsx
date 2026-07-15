import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { LOGIN_ROUTE } from '../../features/auth/constants';
import { useAuthStore } from '../../features/auth/store/authStore';

type Props = {
  // 미지정 시 중첩 라우트(Outlet) 렌더 — router.tsx는 AppShell 부모 라우트를 감싸는 방식(children 없음)과
  // 셸 밖 개별 업무 라우트를 감싸는 방식(children 있음)을 함께 사용한다(React_코드_컨벤션.md §7).
  children?: ReactNode;
};

// 인증 가드 — useAuthStore.user 미존재(미인증) 시 /login으로 리다이렉트.
// app/AuthGate.tsx가 앱 부트스트랩 시 getMe()로 authStore.user를 복원한 뒤에만 라우터(children)를
// 렌더하므로, 이 컴포넌트가 평가되는 시점의 user는 항상 복원이 끝난 authoritative 값이다
// (PR #232 P2-1 — 새로고침 직후 복원 전 오탐 리다이렉트 방지).
export function ProtectedRoute({ children }: Props) {
  const user = useAuthStore((state) => state.user);
  const location = useLocation();

  if (!user) {
    // 로그인 성공 후 원래 목적지로 복귀할 수 있게 현재 경로를 state.from으로 보존(P3-2) —
    // LoginPage가 location.state?.from을 읽어 복귀, 없으면 기존대로 /dashboard
    return (
      <Navigate
        to={LOGIN_ROUTE}
        replace
        state={{ from: `${location.pathname}${location.search}` }}
      />
    );
  }

  return children ? <>{children}</> : <Outlet />;
}
