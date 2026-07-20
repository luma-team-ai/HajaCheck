import { useNavigate, useLocation } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
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
      <a className="landing-login" href="/login">
        로그인
      </a>
    </header>
  );
}
