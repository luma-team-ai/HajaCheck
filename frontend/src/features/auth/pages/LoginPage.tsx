import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { Button } from '../../../shared/components/Button';
import { isSafeInternalPath } from '../../../shared/utils/safeInternalPath';
import { authApi } from '../api/authApi';
import { AuthSignupCta } from '../components/AuthSignupCta';
import { CompanyLoginTab } from '../components/CompanyLoginTab';
import { LoginHeroPanel } from '../components/LoginHeroPanel';
import { PersonalLoginTab } from '../components/PersonalLoginTab';
import { AUTH_ME_QUERY_KEY, AUTH_ME_QUERY_STALE_TIME_MS } from '../constants';
import { useAuthStore } from '../store/authStore';
import type { UserResponse } from '../types';

type AuthTab = 'personal' | 'company';

export function LoginPage() {
  const [activeTab, setActiveTab] = useState<AuthTab>('personal');
  const navigate = useNavigate();
  const location = useLocation();
  const setUser = useAuthStore((state) => state.setUser);

  // CSRF 쿠키 프리밍 겸 세션 확인 — 서버 상태는 React Query로(React_코드_컨벤션.md §4)
  // AuthGate(app/AuthGate.tsx)와 동일 queryKey·staleTime 공유 — 로그아웃 직후 useLogout이
  // setQueryData(AUTH_ME_QUERY_KEY, null)로 고정한 값이 staleTime 동안 fresh로 간주돼,
  // /login으로 전환되며 이 컴포넌트가 마운트돼도 getMe를 즉시 재요청하지 않는다.
  // 즉시 재요청하면 로그아웃 API가 실패해 쿠키가 아직 유효한 경우 세션이 재복원된다(PR #232 P2-D).
  const {
    data: me,
    isSuccess,
    isError,
    error: sessionCheckError,
    refetch: retrySessionCheck,
  } = useQuery<UserResponse, ApiError>({
    queryKey: AUTH_ME_QUERY_KEY,
    queryFn: () => authApi.getMe().then((res) => res.data),
    retry: false,
    staleTime: AUTH_ME_QUERY_STALE_TIME_MS,
    // AuthGate(app/AuthGate.tsx)와 동일 옵션 — 로그아웃 API 실패로 쿠키가 아직 유효할 때,
    // staleTime 경과 후 탭 포커스 복귀/재연결로 이 쿼리가 재요청되면 200→setUser→navigate로
    // 세션이 재복원된다(#280 P2). 포커스/재연결 refetch를 꺼서 이 트리거를 없앤다.
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  useEffect(() => {
    // 이미 로그인 상태면 원래 가려던 목적지(ProtectedRoute가 state.from으로 보존, P3-2)로 이동,
    // 없으면 기존대로 대시보드 — 네비게이션은 데이터 fetching이 아니라 부수효과이므로 useEffect 유지
    if (isSuccess && me) {
      setUser(me);
      const from = (location.state as { from?: string } | null)?.from;
      // state.from은 라우터 state에 실린 값이라 외부에서 임의로 주입 가능 — 내부 절대경로임을
      // 검증하지 않고 그대로 navigate에 넘기면 오픈 리다이렉트로 악용될 수 있다(#280 P3).
      navigate(isSafeInternalPath(from) ? from : '/dashboard');
    }
  }, [isSuccess, me, navigate, setUser, location.state]);

  // 401(미로그인)은 정상 흐름 — 로그인 폼을 그대로 노출한다.
  // 5xx/네트워크 오류는 로그인 여부를 알 수 없으므로, 로그인된 사용자에게 무단으로
  // 로그인 화면을 보여주는 대신 별도 에러 상태 + 재시도를 노출한다.
  const isSessionCheckUnavailable = isError && sessionCheckError.status !== 401;

  if (isSessionCheckUnavailable) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-surface-muted p-6 text-center">
        <p className="m-0 text-base text-text-default">
          로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.
        </p>
        <Button onClick={() => retrySessionCheck()}>다시 시도</Button>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted p-6">
      <div className="flex w-full max-w-[1080px] overflow-hidden rounded-[20px] border border-border bg-surface shadow-sm">
        <LoginHeroPanel />

        <section className="flex w-full flex-col justify-center gap-8 p-12 lg:w-1/2">
          <div className="flex gap-8 border-b border-border" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'personal'}
              className={`-mb-px border-b-2 px-1 py-3 text-base font-semibold ${
                activeTab === 'personal' ? 'border-primary text-heading' : 'border-transparent text-text-muted'
              }`}
              onClick={() => setActiveTab('personal')}
            >
              개인회원
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'company'}
              className={`-mb-px border-b-2 px-1 py-3 text-base font-semibold ${
                activeTab === 'company' ? 'border-primary text-heading' : 'border-transparent text-text-muted'
              }`}
              onClick={() => setActiveTab('company')}
            >
              기업회원
            </button>
          </div>

          {activeTab === 'personal' ? <PersonalLoginTab /> : <CompanyLoginTab />}

          {/* lg 미만(1024px 미만)에서는 LoginHeroPanel 전체가 hidden이라, 그 안에만 있는
              "기업 통합회원 가입"·"개인 회원가입" 진입점이 화면에서 완전히 사라진다(PR #297 P2).
              동일 CTA를 인증 패널 하단에도 렌더하되 lg 이상에서는 숨겨 시안(데스크톱)과
              동일하게 유지한다 — 데스크톱은 LoginHeroPanel 쪽 CTA만 보임(중복 노출 없음). */}
          <div className="lg:hidden" data-testid="mobile-signup-cta">
            <AuthSignupCta />
          </div>
        </section>
      </div>
    </div>
  );
}
