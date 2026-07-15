import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import { CompanyLoginTab } from '../components/CompanyLoginTab';
import { LoginHeroPanel } from '../components/LoginHeroPanel';
import { PersonalLoginTab } from '../components/PersonalLoginTab';
import { AUTH_ME_QUERY_KEY, AUTH_ME_QUERY_STALE_TIME_MS } from '../constants';
import { useAuthStore } from '../store/authStore';
import type { UserResponse } from '../types';
import '../auth.css';

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
  });

  useEffect(() => {
    // 이미 로그인 상태면 원래 가려던 목적지(ProtectedRoute가 state.from으로 보존, P3-2)로 이동,
    // 없으면 기존대로 대시보드 — 네비게이션은 데이터 fetching이 아니라 부수효과이므로 useEffect 유지
    if (isSuccess && me) {
      setUser(me);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from ?? '/dashboard');
    }
  }, [isSuccess, me, navigate, setUser, location.state]);

  // 401(미로그인)은 정상 흐름 — 로그인 폼을 그대로 노출한다.
  // 5xx/네트워크 오류는 로그인 여부를 알 수 없으므로, 로그인된 사용자에게 무단으로
  // 로그인 화면을 보여주는 대신 별도 에러 상태 + 재시도를 노출한다.
  const isSessionCheckUnavailable = isError && sessionCheckError.status !== 401;

  if (isSessionCheckUnavailable) {
    return (
      <div className="login-page login-page--session-error">
        <p className="login-session-error-message">
          로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.
        </p>
        <button
          type="button"
          className="login-session-error-retry-btn"
          onClick={() => retrySessionCheck()}
        >
          다시 시도
        </button>
      </div>
    );
  }

  return (
    <div className="login-page">
      <LoginHeroPanel />

      <section className="login-auth-panel">
        <div className="login-auth-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'personal'}
            className={`login-auth-tab${activeTab === 'personal' ? ' login-auth-tab--active' : ''}`}
            onClick={() => setActiveTab('personal')}
          >
            개인회원
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'company'}
            className={`login-auth-tab${activeTab === 'company' ? ' login-auth-tab--active' : ''}`}
            onClick={() => setActiveTab('company')}
          >
            기업회원
          </button>
        </div>

        {activeTab === 'personal' ? <PersonalLoginTab /> : <CompanyLoginTab />}
      </section>
    </div>
  );
}
