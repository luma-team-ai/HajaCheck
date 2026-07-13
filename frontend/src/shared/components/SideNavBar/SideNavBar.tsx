import { useState } from 'react';
import brandMark from '../../../assets/brand/brand-mark.png';
import collapseIcon from '../../../assets/brand/sidenav-collapse.svg';
import dashboardIcon from '../../../assets/brand/sidenav-dashboard.svg';
import chevronIcon from '../../../assets/brand/sidenav-chevron.svg';
import facilitiesIcon from '../../../assets/brand/sidenav-facilities.svg';
import inspectionsIcon from '../../../assets/brand/sidenav-inspections.svg';
import defectsIcon from '../../../assets/brand/sidenav-defects.svg';
import reportsIcon from '../../../assets/brand/sidenav-reports.svg';
import supportIcon from '../../../assets/brand/sidenav-support.svg';
import mypageIcon from '../../../assets/brand/sidenav-mypage.svg';
import settingsIcon from '../../../assets/brand/sidenav-settings.svg';
import logoutIcon from '../../../assets/brand/sidenav-logout.svg';
import adminIcon from '../../../assets/brand/sidenav-admin.svg';
import './SideNavBar.css';

export interface SideNavSubItem {
  label: string;
  href: string;
}

export interface SideNavItem {
  label: string;
  href: string;
  icon: string;
  subItems?: SideNavSubItem[];
}

interface SideNavBarUser {
  name: string;
  plan?: string;
  avatarUrl?: string;
}

interface SideNavBarProps {
  items?: SideNavItem[];
  adminItem?: SideNavItem;
  isAdmin?: boolean;
  activeHref?: string;
  user?: SideNavBarUser;
  onLogout?: () => void;
  onCollapseToggle?: () => void;
}

// Figma node-id 151-967 "대시보드SideNavBar"(일반) / 163-663 "관리자 SideNavBar"(관리자) 기준
const DEFAULT_ITEMS: SideNavItem[] = [
  {
    label: '대시보드',
    href: '/dashboard',
    icon: dashboardIcon,
    subItems: [
      { label: '전체 시설물 현황', href: '/dashboard/facilities-overview' },
      { label: '다음 점검일 도래', href: '/dashboard/upcoming-inspections' },
      { label: 'AI 주간 브리핑 카드', href: '/dashboard/ai-weekly-briefing' },
    ],
  },
  { label: '시설물 관리', href: '/facilities', icon: facilitiesIcon },
  { label: '점검 관리', href: '/inspections', icon: inspectionsIcon },
  { label: '하자 관리', href: '/defects', icon: defectsIcon },
  { label: '보고서', href: '/reports', icon: reportsIcon },
  { label: '고객지원', href: '/support', icon: supportIcon },
  {
    label: '마이페이지',
    href: '/my-page',
    icon: mypageIcon,
    subItems: [
      { label: '내 정보', href: '/my-page/profile' },
      { label: '내 점검 이력', href: '/my-page/inspections' },
      { label: '내 보고서', href: '/my-page/reports' },
      { label: '내 플랜', href: '/my-page/plan' },
      { label: '내 상담 내역', href: '/my-page/counsels' },
    ],
  },
  { label: '설정', href: '/settings', icon: settingsIcon },
];

const DEFAULT_ADMIN_ITEM: SideNavItem = {
  label: '관리자 페이지',
  href: '/admin',
  icon: adminIcon,
  subItems: [
    { label: '사용자 관리', href: '/admin/users' },
    { label: '플랜·쿼터 관리', href: '/admin/plans-quota' },
    { label: '하자 유형·등급 관리', href: '/admin/defect-types' },
    { label: '상담 관리', href: '/admin/counsels' },
    { label: 'RAG 문서 관리', href: '/admin/rag-documents' },
    { label: '서비스 통계', href: '/admin/stats' },
    { label: '시스템 모니터링', href: '/admin/monitoring' },
  ],
};

export function SideNavBar({
  items = DEFAULT_ITEMS,
  adminItem = DEFAULT_ADMIN_ITEM,
  isAdmin = false,
  activeHref,
  user,
  onLogout,
  onCollapseToggle,
}: SideNavBarProps) {
  const allItems = isAdmin ? [...items, adminItem] : items;
  const defaultExpanded = allItems.find((item) =>
    item.subItems?.some((sub) => sub.href === activeHref),
  )?.label;
  const [expandedLabel, setExpandedLabel] = useState<string | undefined>(defaultExpanded);

  function toggleExpand(label: string) {
    setExpandedLabel((current) => (current === label ? undefined : label));
  }

  return (
    <aside className="side-nav">
      <div className="side-nav-top">
        <div className="side-nav-logo">
          <img className="side-nav-logo-mark" src={brandMark} alt="" />
          <span>HajaCheck</span>
          {isAdmin && <span className="side-nav-admin-badge">ADMIN</span>}
        </div>
        {onCollapseToggle && (
          <button
            type="button"
            className="side-nav-collapse"
            onClick={onCollapseToggle}
            aria-label="사이드바 접기"
          >
            <img src={collapseIcon} alt="" />
          </button>
        )}
      </div>

      <nav className="side-nav-links" aria-label="사이드 메뉴">
        {allItems.map((item) =>
          item.subItems ? (
            <div key={item.href} className="side-nav-group">
              <button
                type="button"
                className="side-nav-link side-nav-link--group"
                onClick={() => toggleExpand(item.label)}
                aria-expanded={expandedLabel === item.label}
              >
                <span className="side-nav-link-main">
                  <img className="side-nav-link-icon" src={item.icon} alt="" />
                  {item.label}
                </span>
                <img
                  className={`side-nav-chevron${expandedLabel === item.label ? ' side-nav-chevron--open' : ''}`}
                  src={chevronIcon}
                  alt=""
                />
              </button>
              {expandedLabel === item.label && (
                <div className="side-nav-sublist">
                  {item.subItems.map((sub) => (
                    <a
                      key={sub.href}
                      href={sub.href}
                      className={`side-nav-sublink${sub.href === activeHref ? ' side-nav-sublink--active' : ''}`}
                      aria-current={sub.href === activeHref ? 'page' : undefined}
                    >
                      {sub.label}
                    </a>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <a
              key={item.href}
              href={item.href}
              className={`side-nav-link${item.href === activeHref ? ' side-nav-link--active' : ''}`}
              aria-current={item.href === activeHref ? 'page' : undefined}
            >
              <span className="side-nav-link-main">
                <img className="side-nav-link-icon" src={item.icon} alt="" />
                {item.label}
              </span>
            </a>
          ),
        )}
      </nav>

      {user && (
        <div className="side-nav-user">
          <div className="side-nav-user-info">
            {user.avatarUrl ? (
              <img className="side-nav-user-avatar" src={user.avatarUrl} alt="" />
            ) : (
              <span
                className="side-nav-user-avatar side-nav-user-avatar--placeholder"
                aria-hidden="true"
              />
            )}
            <span className="side-nav-user-text">
              <span className="side-nav-user-name">{user.name}</span>
              {user.plan && <span className="side-nav-user-plan">{user.plan}</span>}
            </span>
          </div>
        </div>
      )}

      {onLogout && (
        <button type="button" className="side-nav-logout" onClick={onLogout}>
          <img src={logoutIcon} alt="" />
          로그아웃
        </button>
      )}
    </aside>
  );
}
