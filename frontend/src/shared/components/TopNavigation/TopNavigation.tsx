import { Link } from 'react-router-dom';
import brandMark from '../../../assets/brand/brand-mark.png';
import './TopNavigation.css';

export interface TopNavItem {
  label: string;
  href: string;
}

interface TopNavigationProps {
  navItems?: TopNavItem[];
  loginHref?: string;
  logoHref?: string;
}

// Figma node-id 63-2 "Top Navigation" 기준 — 로그인 전 랜딩용 최상단 내비게이션
const DEFAULT_NAV_ITEMS: TopNavItem[] = [
  { label: '대시보드', href: '/dashboard' },
  { label: '검사 관리', href: '/inspections' },
  { label: '시설물 정보', href: '/facilities' },
  { label: 'AI 분석', href: '/ai-analysis' },
  { label: '고객지원', href: '/support' },
];

export function TopNavigation({
  navItems = DEFAULT_NAV_ITEMS,
  loginHref = '/login',
  logoHref = '/',
}: TopNavigationProps) {
  return (
    <div className="top-nav">
      <Link className="top-nav-logo" to={logoHref}>
        <img className="top-nav-logo-mark" src={brandMark} alt="" />
        <span>HajaCheck</span>
      </Link>

      <nav className="top-nav-links" aria-label="주요 메뉴">
        {navItems.map((item) => (
          <Link key={item.href} className="top-nav-link" to={item.href}>
            {item.label}
          </Link>
        ))}
      </nav>

      <Link to={loginHref} className="top-nav-login">
        로그인
      </Link>
    </div>
  );
}
