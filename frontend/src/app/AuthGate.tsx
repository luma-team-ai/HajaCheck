import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { authApi } from '../features/auth/api/authApi';
import { AUTH_ME_QUERY_KEY, AUTH_ME_QUERY_STALE_TIME_MS } from '../features/auth/constants';
import { useAuthStore } from '../features/auth/store/authStore';
import type { UserResponse } from '../features/auth/types';
import type { ApiError } from '../shared/api/types';

type Props = {
  children: ReactNode;
};

// 앱 부트스트랩 인증 복원 게이트(PR #232 P2-1, #231 재작업으로 새 셸에 이식) — 새로고침 시 세션 쿠키는
// 유효해도 authStore.user는 초기값(null)이라, 게이트 없이 바로 라우터를 렌더하면 ProtectedRoute가
// 복원 완료 전에 user=null을 보고 로그인된 사용자를 /login으로 튕겨낸다.
// getMe()가 settle(성공/실패 확정)될 때까지 children(RouterProvider)을 렌더하지 않아
// ProtectedRoute가 항상 "복원이 끝난" user를 기준으로 판단하게 한다.
// LoginPage의 세션 체크와 동일 queryKey(['auth','me'])라 react-query 캐시를 공유 — 중복 호출 없음.
export function AuthGate({ children }: Props) {
  const setUser = useAuthStore((state) => state.setUser);
  const storeUser = useAuthStore((state) => state.user);
  // 부트스트랩이 한 번 끝나면 true로 고정 — AuthGate는 main.tsx에서 앱 수명 내내 마운트 상태를
  // 유지하므로(라우트 전환으로 언마운트되지 않음), 이 컴포넌트 자체의 useQuery 옵저버가
  // 이후(예: 로그아웃의 setQueryData) 캐시 변화를 봐도 스플래시/에러 화면으로 되돌아가지
  // 않게 한다(PR #232 P2-C).
  const [ready, setReady] = useState(false);

  const {
    data: me,
    isPending,
    isSuccess,
    isError,
    error,
    refetch,
  } = useQuery<UserResponse, ApiError>({
    queryKey: AUTH_ME_QUERY_KEY,
    queryFn: () => authApi.getMe().then((res) => res.data),
    retry: false,
    staleTime: AUTH_ME_QUERY_STALE_TIME_MS,
    // 부트스트랩 게이트 쿼리가 탭 포커스/네트워크 재연결 시점에 얽혀 다시 pending/성공 판정을
    // 타는 것을 막는다 — ready 고정 이후 재평가는 하지 않지만, ready 이전 구간에서도 타이밍
    // 의존(포커스 전환 등)으로 스플래시가 불필요하게 재계산되지 않도록 견고화(#231 handoff).
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  // 부트스트랩 첫 성공에서만 authStore를 채운다 — ready 이후 이 쿼리가 다시 성공해도
  // (다른 옵저버의 refetch 등) user를 재차 채우지 않는다(PR #232 P2-D, 세션 재복원 방지).
  useEffect(() => {
    if (!ready && isSuccess && me) {
      setUser(me);
    }
  }, [ready, isSuccess, me, setUser]);

  // getMe 성공 시 authStore 동기화(setUser)는 useEffect로 한 프레임 늦게 반영된다.
  // isPending만으로 게이트를 열면, isPending이 false로 바뀐 그 프레임에는 storeUser가 아직
  // null이라 ProtectedRoute가 오탐 리다이렉트할 수 있다 — 성공 케이스는 storeUser가
  // 채워질 때까지 게이트를 유지한다. 단, 200이어도 me가 falsy(빈 응답)면 setUser가 영영
  // 호출되지 않아 storeUser가 null로 고정되므로(PR #232 P2-B), 이 경우는 게이트를 열어
  // 미인증으로 취급한다(!!me 체크로 데드락 방지). ready가 되면 이 판정 자체를 더 이상 하지 않는다.
  const isRestoring = !ready && (isPending || (isSuccess && !!me && !storeUser));

  // getMe는 skipAuthRedirect라 401(미로그인)이 하드 리다이렉트되지 않고 여기로 들어온다 —
  // 401은 미인증 정상 신호이므로 children을 그대로 렌더하고(공개 랜딩 '/'도 보이게, #276),
  // 보호 라우트 접근은 ProtectedRoute가 가드한다. 반면 5xx/네트워크 오류는 로그인 여부를 알 수
  // 없으므로 유효 세션을 곧바로 미인증으로 강등시키지 않고, LoginPage 세션체크와 동일 정책
  // (status !== 401 → 에러+재시도 안내)으로 처리한다(PR #232 P2-A).
  // ready 이후에는 이 판정도 재평가하지 않는다(부트스트랩 이후 일시적 5xx로 화면이 되돌아가면 안 됨).
  const isSessionCheckUnavailable = !ready && isError && error.status !== 401;

  // 부트스트랩 판정이 끝나(스플래시도 에러 화면도 아님) children을 렌더하기로 한 첫 순간에
  // ready를 한 번만 true로 고정한다 — 이후에는 캐시가 바뀌어도(로그아웃의 setQueryData 등)
  // 스플래시/에러 화면으로 되돌아가지 않고 항상 children을 렌더한다(PR #232 P2-C).
  useEffect(() => {
    if (!ready && !isRestoring && !isSessionCheckUnavailable) {
      setReady(true);
    }
  }, [ready, isRestoring, isSessionCheckUnavailable]);

  if (!ready) {
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
  }

  // 401(미인증)을 포함한 settle 상태 — user=null 그대로 렌더,
  // 보호 라우트 접근 시 ProtectedRoute가 정상적으로 /login으로 리다이렉트한다.
  return <>{children}</>;
}
