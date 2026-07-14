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

  useEffect(() => {
    // CSRF 쿠키 프리밍 겸 세션 확인 — 이미 로그인 상태면 대시보드로 이동, 실패(미로그인)는 무시
    authApi
      .getMe()
      .then((res) => {
        setUser(res.data);
        navigate('/dashboard');
      })
      .catch(() => {
        // 미로그인 상태 — 로그인 화면 그대로 노출
      });
    // 마운트 시 1회만 실행 — navigate/setUser는 재실행 트리거로 삼지 않음(참조 안정성 있음: react-router/zustand)
  }, [navigate, setUser]);

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
