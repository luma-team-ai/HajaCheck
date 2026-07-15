import type { ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../../features/auth/store/authStore';

type Props = {
  // 미지정 시 중첩 라우트(Outlet) 렌더 — router.tsx는 현재 라우트별 element를 직접 감싸는
  // children 방식으로 사용한다(React_코드_컨벤션.md §7 "인증 가드: ProtectedRoute 공통 컴포넌트")
  children?: ReactNode;
};

// 인증 가드 — useAuthStore.user 미존재(미인증) 시 /login으로 리다이렉트.
// 세션 쿠키가 진실 소스라 새로고침 시 getMe() 복원 전에는 user가 잠깐 null일 수 있으나,
// 이 컴포넌트 범위에서는 authStore 값만 판단 기준으로 삼는다(복원 타이밍은 별도 관심사).
export function ProtectedRoute({ children }: Props) {
  const user = useAuthStore((state) => state.user);

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
