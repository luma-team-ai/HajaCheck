import brandMark from '../../../assets/brand/brand-mark.png';
import './SideNavBar.css';

export interface SideNavItem {
  label: string;
  href: string;
}

interface SideNavBarUser {
  name: string;
  plan?: string;
}

interface SideNavBarProps {
  items?: SideNavItem[];
  activeHref?: string;
  user?: SideNavBarUser;
  onLogout?: () => void;
}

// HAJA-137 "main SideNavBar" — 별도 Figma 노드 링크가 전달되지 않아, 사용자가 공유한
// 원본 화면 텍스트(대시보드/시설물 관리/점검 관리/하자 관리/보고서/고객지원/마이페이지/설정,
// 하단 "김관리 · Standard · 로그아웃")를 근거로 구성. 실제 Figma 시안 확인되면 스타일 조정 필요
const DEFAULT_ITEMS: SideNavItem[] = [
  { label: '대시보드', href: '/dashboard' },
  { label: '시설물 관리', href: '/facilities' },
  { label: '점검 관리', href: '/inspections' },
  { label: '하자 관리', href: '/defects' },
  { label: '보고서', href: '/reports' },
  { label: '고객지원', href: '/support' },
  { label: '마이페이지', href: '/my-page' },
  { label: '설정', href: '/settings' },
];

export function SideNavBar({ items = DEFAULT_ITEMS, activeHref, user, onLogout }: SideNavBarProps) {
  return (
    <aside className="side-nav">
      <div className="side-nav-logo">
        <img className="side-nav-logo-mark" src={brandMark} alt="" />
        <span>HajaCheck</span>
      </div>

      <nav className="side-nav-links" aria-label="사이드 메뉴">
        {items.map((item) => (
          <a
            key={item.href}
            href={item.href}
            className={`side-nav-link${item.href === activeHref ? ' side-nav-link--active' : ''}`}
            aria-current={item.href === activeHref ? 'page' : undefined}
          >
            {item.label}
          </a>
        ))}
      </nav>

      {user && (
        <div className="side-nav-user">
          <div className="side-nav-user-info">
            <span className="side-nav-user-name">{user.name}</span>
            {user.plan && <span className="side-nav-user-plan">{user.plan}</span>}
          </div>
          {onLogout && (
            <button type="button" className="side-nav-logout" onClick={onLogout}>
              로그아웃
            </button>
          )}
        </div>
      )}
    </aside>
  );
}
