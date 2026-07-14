import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { ApiError } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import { CompanyLoginTab } from '../components/CompanyLoginTab';
import { LoginHeroPanel } from '../components/LoginHeroPanel';
import { PersonalLoginTab } from '../components/PersonalLoginTab';
import { useAuthStore } from '../store/authStore';
import type { UserResponse } from '../types';
import '../auth.css';

type AuthTab = 'personal' | 'company';

export function LoginPage() {
  const [activeTab, setActiveTab] = useState<AuthTab>('personal');
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);

  // CSRF 쿠키 프리밍 겸 세션 확인 — 서버 상태는 React Query로(React_코드_컨벤션.md §4)
  const {
    data: me,
    isSuccess,
    isError,
    error: sessionCheckError,
    refetch: retrySessionCheck,
  } = useQuery<UserResponse, ApiError>({
    queryKey: ['auth', 'me'],
    queryFn: () => authApi.getMe().then((res) => res.data),
    retry: false,
  });

  useEffect(() => {
    // 이미 로그인 상태면 대시보드로 이동 — 네비게이션은 데이터 fetching이 아니라 부수효과이므로 useEffect 유지
    if (isSuccess && me) {
      setUser(me);
      navigate('/dashboard');
    }
  }, [isSuccess, me, navigate, setUser]);

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
