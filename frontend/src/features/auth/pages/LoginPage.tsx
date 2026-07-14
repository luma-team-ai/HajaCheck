import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { CompanyLoginTab } from '../components/CompanyLoginTab';
import { LoginHeroPanel } from '../components/LoginHeroPanel';
import { PersonalLoginTab } from '../components/PersonalLoginTab';
import { useAuthStore } from '../store/authStore';
import '../auth.css';

type AuthTab = 'personal' | 'company';

export function LoginPage() {
  const [activeTab, setActiveTab] = useState<AuthTab>('personal');
  const navigate = useNavigate();
  const setUser = useAuthStore((state) => state.setUser);

  // CSRF 쿠키 프리밍 겸 세션 확인 — 서버 상태는 React Query로(React_코드_컨벤션.md §4), 실패(미로그인)는 무시
  const { data: me, isSuccess } = useQuery({
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
