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

  const {
    data: me,
    isPending,
    isSuccess,
    isError,
    error,
    refetch,
  } = useQuery<UserResponse, ApiError>({
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
  // 채워질 때까지 게이트를 유지한다. 단, 200이어도 me가 falsy(빈 응답)면 setUser가 영영
  // 호출되지 않아 storeUser가 null로 고정되므로(PR #232 P2-B), 이 경우는 게이트를 열어
  // 미인증으로 취급한다(!!me 체크로 데드락 방지).
  const isRestoring = isPending || (isSuccess && !!me && !storeUser);

  if (isRestoring) {
    return (
      <div
        className="auth-gate-splash flex min-h-screen items-center justify-center text-sm text-text-muted"
        role="status"
        aria-live="polite"
      >
        불러오는 중...
      </div>
    );
  }

  // shared/api/axios.ts 인터셉터가 401은 이미 하드 리다이렉트(location.href=/login) 처리하므로
  // AuthGate가 보는 isError는 사실상 5xx/네트워크 오류다. 이런 오류는 로그인 여부를 알 수 없으므로
  // 유효 세션을 곧바로 미인증(children/user=null)으로 강등시키지 않고, LoginPage의 세션체크
  // 에러 UI와 동일한 정책(status !== 401 → 에러+재시도)으로 별도 안내한다(PR #232 P2-A).
  const isSessionCheckUnavailable = isError && error.status !== 401;

  if (isSessionCheckUnavailable) {
    return (
      <div
        className="auth-gate-error flex min-h-screen flex-col items-center justify-center gap-4 p-6 text-center"
        role="alert"
      >
        <p className="auth-gate-error-message m-0 text-base text-text-default">
          로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.
        </p>
        <button
          type="button"
          className="auth-gate-error-retry-btn cursor-pointer rounded-lg border-none bg-primary px-5 py-2.5 text-sm font-bold text-surface"
          onClick={() => refetch()}
        >
          다시 시도
        </button>
      </div>
    );
  }

  // 401(미인증)을 포함한 settle 상태 — user=null 그대로 렌더,
  // 보호 라우트 접근 시 ProtectedRoute가 정상적으로 /login으로 리다이렉트한다.
  return <>{children}</>;
}
