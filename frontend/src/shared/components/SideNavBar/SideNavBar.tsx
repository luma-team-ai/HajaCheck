import { useState } from 'react';
import { Link } from 'react-router-dom';
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
  /** 초기 접힘 상태(비제어). 기본값 false — 펼쳐진 상태로 시작 */
  defaultCollapsed?: boolean;
  /** 접기/펼치기 토글마다 호출(레이아웃 쪽에서 margin 조정 등에 사용) */
  onCollapseToggle?: (collapsed: boolean) => void;
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
  {
    label: '시설물 관리',
    href: '/facilities',
    icon: facilitiesIcon,
    subItems: [
      { label: '시설물 목록/등록', href: '/facilities/list' },
      { label: '시설물 상세', href: '/facilities/detail' },
      { label: '점검 주기 설정', href: '/facilities/inspection-cycle' },
      { label: '지도 뷰', href: '/facilities/map' },
    ],
  },
  {
    label: '점검 관리',
    href: '/inspections',
    icon: inspectionsIcon,
    subItems: [
      { label: '점검(회차) 생성', href: '/inspections/create' },
      { label: '촬영 데이터 업로드', href: '/inspections/media-upload' },
      { label: 'AI 분석 실행/상태', href: '/inspections/ai-analysis' },
      { label: '분석 결과 뷰어', href: '/inspections/result-viewer' },
      { label: '보고서 생성 진입점', href: '/inspections/report-entry' },
    ],
  },
  {
    label: '하자 관리',
    href: '/defects',
    icon: defectsIcon,
    subItems: [
      { label: '하자 목록', href: '/defects/list' },
      { label: '하자 상세', href: '/defects/detail' },
    ],
  },
  {
    label: '보고서',
    href: '/reports',
    icon: reportsIcon,
    subItems: [
      { label: '보고서 목록/이력 관리', href: '/reports/list' },
      { label: '보고서 편집·미리보기', href: '/reports/editor' },
      { label: 'PDF 내보내기', href: '/reports/export-pdf' },
    ],
  },
  {
    label: '고객지원',
    href: '/support',
    icon: supportIcon,
    subItems: [
      { label: 'AI 어시스턴트', href: '/support/ai-assistant' },
      { label: '상담 챗봇', href: '/support/chat-bot' },
      { label: '내 상담 이력', href: '/support/history' },
    ],
  },
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
  // TODO: "통계" 전용 아이콘이 Figma에서 아직 안 나와서 보고서(reportsIcon)를 임시로 재사용 중 —
  // 실제 아이콘 나오면 교체 필요
  { label: '통계', href: '/statistics', icon: reportsIcon },
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
  defaultCollapsed = false,
  onCollapseToggle,
}: SideNavBarProps) {
  const allItems = isAdmin ? [...items, adminItem] : items;
  const defaultExpanded = allItems.find((item) =>
    item.subItems?.some((sub) => sub.href === activeHref),
  )?.label;
  const [expandedLabel, setExpandedLabel] = useState<string | undefined>(defaultExpanded);
  const [collapsed, setCollapsed] = useState(defaultCollapsed);

  function toggleExpand(label: string) {
    setExpandedLabel((current) => (current === label ? undefined : label));
  }

  function handleCollapseToggle() {
    setCollapsed((current) => {
      const next = !current;
      onCollapseToggle?.(next);
      return next;
    });
  }

  return (
    <aside className={`side-nav${collapsed ? ' side-nav--collapsed' : ''}`}>
      <div className="side-nav-top">
        <div className="side-nav-logo">
          <img className="side-nav-logo-mark" src={brandMark} alt="" />
          {!collapsed && (
            <>
              <span>HajaCheck</span>
              {isAdmin && <span className="side-nav-admin-badge">ADMIN</span>}
            </>
          )}
        </div>
        <button
          type="button"
          className="side-nav-collapse"
          onClick={handleCollapseToggle}
          aria-label={collapsed ? '사이드바 펼치기' : '사이드바 접기'}
          aria-expanded={!collapsed}
        >
          <img src={collapseIcon} alt="" />
        </button>
      </div>

      <nav className="side-nav-links" aria-label="사이드 메뉴">
        {allItems.map((item) =>
          item.subItems ? (
            <div key={item.href} className="side-nav-group">
              <button
                type="button"
                className="side-nav-link side-nav-link--group"
                onClick={() => toggleExpand(item.label)}
                aria-expanded={!collapsed && expandedLabel === item.label}
                title={collapsed ? item.label : undefined}
              >
                <span className="side-nav-link-main">
                  <img className="side-nav-link-icon" src={item.icon} alt="" />
                  {!collapsed && item.label}
                </span>
                {!collapsed && (
                  <img
                    className={`side-nav-chevron${expandedLabel === item.label ? ' side-nav-chevron--open' : ''}`}
                    src={chevronIcon}
                    alt=""
                  />
                )}
              </button>
              {!collapsed && expandedLabel === item.label && (
                <div className="side-nav-sublist">
                  {item.subItems.map((sub) => (
                    <Link
                      key={sub.href}
                      to={sub.href}
                      className={`side-nav-sublink${sub.href === activeHref ? ' side-nav-sublink--active' : ''}`}
                      aria-current={sub.href === activeHref ? 'page' : undefined}
                    >
                      {sub.label}
                    </Link>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <Link
              key={item.href}
              to={item.href}
              className={`side-nav-link${item.href === activeHref ? ' side-nav-link--active' : ''}`}
              aria-current={item.href === activeHref ? 'page' : undefined}
              title={collapsed ? item.label : undefined}
            >
              <span className="side-nav-link-main">
                <img className="side-nav-link-icon" src={item.icon} alt="" />
                {!collapsed && item.label}
              </span>
            </Link>
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
            {!collapsed && (
              <span className="side-nav-user-text">
                <span className="side-nav-user-name">{user.name}</span>
                {user.plan && <span className="side-nav-user-plan">{user.plan}</span>}
              </span>
            )}
          </div>
        </div>
      )}

      {onLogout && (
        <button
          type="button"
          className="side-nav-logout"
          onClick={onLogout}
          title={collapsed ? '로그아웃' : undefined}
        >
          <img src={logoutIcon} alt="" />
          {!collapsed && '로그아웃'}
        </button>
      )}
    </aside>
  );
}
