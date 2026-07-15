import { Link } from 'react-router-dom';
import brandMark from '../../../assets/brand/brand-mark.png';

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
    <div className="flex max-w-7xl items-center justify-between gap-6 rounded-full border border-white/20 bg-white/50 px-[33px] py-[13px] shadow-[0px_3px_4px_0px_rgba(0,0,0,0.2)] backdrop-blur-[10px]">
      <Link
        className="inline-flex items-center gap-[5px] text-[22px] font-extrabold whitespace-nowrap text-heading no-underline"
        to={logoHref}
      >
        <img className="h-[25px] w-[25px] object-contain" src={brandMark} alt="" />
        <span>HajaCheck</span>
      </Link>

      <nav className="flex items-center gap-8" aria-label="주요 메뉴">
        {navItems.map((item) => (
          <Link
            key={item.href}
            className="text-base font-medium whitespace-nowrap text-text-default no-underline"
            to={item.href}
          >
            {item.label}
          </Link>
        ))}
      </nav>

      <Link
        to={loginHref}
        className="inline-flex items-center justify-center rounded-full bg-primary px-6 py-2.5 text-base font-semibold whitespace-nowrap text-surface no-underline"
      >
        로그인
      </Link>
    </div>
  );
}
