import { useNavigate, useLocation } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { useAuthStore } from '../../auth/store/authStore';
import { INVITE_CODE_ROUTE, LOGIN_ROUTE } from '../../../shared/constants/routes';
import { NAV_ITEMS } from '../constants';

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function scrollToSection(targetId: string) {
  document.getElementById(targetId)?.scrollIntoView({ behavior: 'smooth' });
}

export function LandingHeader() {
  const navigate = useNavigate();
  const location = useLocation();
  const isLandingPage = location.pathname === '/';
  // 초대 코드 미입력(status=WAITING) 사용자는 이미 로그인 상태라 "로그인"이 맞지 않는다.
  // /login으로 보내도 세션이 살아 있어 /dashboard → /invite-code로 튕기므로, 라벨과 목적지를
  // 초대 코드 화면으로 직결해 돌아올 통로를 명확히 한다(LandingPage의 WAITING 예외와 한 쌍).
  const isWaitingForInviteCode = useAuthStore((state) => state.user?.status === 'WAITING');

  function handleLogoClick() {
    if (isLandingPage) {
      scrollToTop();
    } else {
      navigate('/');
    }
  }

  function handleNavClick(targetId: string) {
    if (isLandingPage) {
      scrollToSection(targetId);
    } else {
      navigate(`/#${targetId}`);
    }
  }

  return (
    <header className="landing-header">
      <button type="button" className="landing-logo-button" onClick={handleLogoClick} aria-label="HajaCheck 홈으로">
        <img className="landing-logo-image" src={brandLogo} alt="HajaCheck" />
      </button>
      <nav className="landing-nav">
        {NAV_ITEMS.map((item) => (
          <button key={item.label} type="button" onClick={() => handleNavClick(item.targetId)}>
            {item.label}
          </button>
        ))}
      </nav>
      <a className="landing-login" href={isWaitingForInviteCode ? INVITE_CODE_ROUTE : LOGIN_ROUTE}>
        {isWaitingForInviteCode ? '초대 코드 입력' : '로그인'}
      </a>
    </header>
  );
}
