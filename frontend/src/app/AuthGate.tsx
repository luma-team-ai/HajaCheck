import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useEffect } from 'react';
import { authApi } from '../features/auth/api/authApi';
import { useAuthStore } from '../features/auth/store/authStore';
import type { UserResponse } from '../features/auth/types';
import type { ApiError } from '../shared/api/types';

type Props = {
  children: ReactNode;
};

// 앱 부트스트랩 인증 복원 게이트(PR #232 P2-1) — 새로고침 시 세션 쿠키는 유효해도
// authStore.user는 초기값(null)이라, 게이트 없이 바로 라우터를 렌더하면 ProtectedRoute가
// 복원 완료 전에 user=null을 보고 로그인된 사용자를 /login으로 튕겨낸다.
// getMe()가 settle(성공/실패 확정)될 때까지 children(RouterProvider)을 렌더하지 않아
// ProtectedRoute가 항상 "복원이 끝난" user를 기준으로 판단하게 한다.
// LoginPage의 세션 체크와 동일 queryKey(['auth','me'])라 react-query 캐시를 공유 — 중복 호출 없음.
export function AuthGate({ children }: Props) {
  const setUser = useAuthStore((state) => state.setUser);
  const storeUser = useAuthStore((state) => state.user);

  const { data: me, isPending, isSuccess } = useQuery<UserResponse, ApiError>({
    queryKey: ['auth', 'me'],
    queryFn: () => authApi.getMe().then((res) => res.data),
    retry: false,
  });

  useEffect(() => {
    if (isSuccess && me) {
      setUser(me);
    }
  }, [isSuccess, me, setUser]);

  // getMe 성공 시 authStore 동기화(setUser)는 useEffect로 한 프레임 늦게 반영된다.
  // isPending만으로 게이트를 열면, isPending이 false로 바뀐 그 프레임에는 storeUser가 아직
  // null이라 ProtectedRoute가 오탐 리다이렉트할 수 있다 — 성공 케이스는 storeUser가
  // 채워질 때까지 게이트를 유지한다. (401 등 에러는 store가 채워질 일이 없으므로 즉시 통과)
  const isRestoring = isPending || (isSuccess && !storeUser);

  if (isRestoring) {
    return (
      <div className="auth-gate-splash" role="status" aria-live="polite">
        불러오는 중...
      </div>
    );
  }

  // 401(미로그인)을 포함한 에러도 settle된 상태 — user=null 그대로 렌더,
  // 보호 라우트 접근 시 ProtectedRoute가 정상적으로 /login으로 리다이렉트한다.
  return <>{children}</>;
}
