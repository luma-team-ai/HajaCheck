import { useEffect, useState } from 'react';
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

const LINK_BASE =
  'flex w-full items-center rounded-full border-none bg-none text-base font-medium text-text-default no-underline cursor-pointer hover:bg-surface-muted hover:text-primary';

const LOGOUT_BASE =
  'inline-flex w-fit items-center gap-3 border-none bg-none text-sm font-medium text-[#3f3f46] cursor-pointer';

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
  const [expandedLabel, setExpandedLabel] = useState<string | undefined>(() =>
    allItems.find((item) => item.subItems?.some((sub) => sub.href === activeHref))?.label,
  );
  const [collapsed, setCollapsed] = useState(defaultCollapsed);

  // activeHref가 마운트 이후 바뀌어도(사이드바 클릭이 아닌 다른 경로로 하위 라우트 진입 시) 해당 그룹이 펼쳐지도록 동기화
  useEffect(() => {
    const activeGroupLabel = allItems.find((item) =>
      item.subItems?.some((sub) => sub.href === activeHref),
    )?.label;
    if (activeGroupLabel) {
      setExpandedLabel(activeGroupLabel);
    }
  }, [activeHref, allItems]);

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

  function getLinkClassName(isActive: boolean, isGroup = false) {
    const active = isActive ? ' bg-surface text-primary ring-1 ring-border' : '';
    const layout = collapsed ? 'justify-center p-2' : `${isGroup ? 'justify-between ' : ''}px-4 py-2`;
    return `${LINK_BASE} ${layout}${active}`;
  }

  return (
    <aside
      className={`flex min-h-full flex-col border-r border-border bg-surface text-sm transition-[width] duration-150 ${
        collapsed ? 'w-18 px-2 py-4' : 'w-60 p-4'
      }`}
    >
      <div
        className={`flex border-b border-border pb-4 mb-1 ${
          collapsed ? 'flex-col h-auto justify-center gap-3' : 'items-center justify-between gap-2 h-16'
        }`}
      >
        <div
          className={`flex items-center gap-1.5 text-sm font-semibold text-primary ${collapsed ? 'justify-center' : ''}`}
        >
          <img className="h-4 w-4 object-contain" src={brandMark} alt="" />
          {!collapsed && (
            <>
              <span>HajaCheck</span>
              {isAdmin && (
                <span className="rounded-full border border-[#e5e7eb] bg-[#f3f4f6] px-[7px] py-[3px] text-[10px] tracking-[0.05em] text-[#6b7280]">
                  ADMIN
                </span>
              )}
            </>
          )}
        </div>
        <button
          type="button"
          className="inline-flex h-5 w-5 cursor-pointer items-center justify-center border-none bg-none p-0"
          onClick={handleCollapseToggle}
          aria-label={collapsed ? '사이드바 펼치기' : '사이드바 접기'}
          aria-expanded={!collapsed}
        >
          <img className="h-5 w-5" src={collapseIcon} alt="" />
        </button>
      </div>

      <nav className="flex flex-1 flex-col gap-2 pt-1" aria-label="사이드 메뉴">
        {allItems.map((item) =>
          item.subItems ? (
            <div key={item.href}>
              <button
                type="button"
                className={getLinkClassName(false, true)}
                onClick={() => toggleExpand(item.label)}
                aria-expanded={!collapsed && expandedLabel === item.label}
                title={collapsed ? item.label : undefined}
              >
                <span className={`inline-flex items-center ${collapsed ? 'gap-0' : 'gap-3'}`}>
                  <img className="h-[18px] w-[18px] object-contain" src={item.icon} alt="" />
                  {!collapsed && item.label}
                </span>
                {!collapsed && (
                  <img
                    className={`h-[5.55px] w-[9px] transition-transform duration-150 ${
                      expandedLabel === item.label ? 'rotate-180' : ''
                    }`}
                    src={chevronIcon}
                    alt=""
                  />
                )}
              </button>
              {!collapsed && expandedLabel === item.label && (
                <div className="flex flex-col gap-1 pr-4 pl-[46px]">
                  {item.subItems.map((sub) => (
                    <Link
                      key={sub.href}
                      to={sub.href}
                      className={`rounded-full px-4 py-[6px] text-[13px] no-underline hover:text-primary ${
                        sub.href === activeHref
                          ? 'bg-surface text-primary ring-1 ring-border'
                          : 'text-[#71717a]'
                      }`}
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
              className={getLinkClassName(item.href === activeHref)}
              aria-current={item.href === activeHref ? 'page' : undefined}
              title={collapsed ? item.label : undefined}
            >
              <span className={`inline-flex items-center ${collapsed ? 'gap-0' : 'gap-3'}`}>
                <img className="h-[18px] w-[18px] object-contain" src={item.icon} alt="" />
                {!collapsed && item.label}
              </span>
            </Link>
          ),
        )}
      </nav>

      {user && (
        <div className="border-t border-[#cbc4d2]/30 px-4 pt-[17px] pb-2.5">
          <div className={`flex items-center gap-2 ${collapsed ? 'justify-center' : ''}`}>
            {user.avatarUrl ? (
              <img
                className="h-8 w-8 rounded-full bg-[#cbc4d2] object-cover"
                src={user.avatarUrl}
                alt=""
              />
            ) : (
              <span
                className="inline-block h-8 w-8 rounded-full bg-[#cbc4d2] object-cover"
                aria-hidden="true"
              />
            )}
            {!collapsed && (
              <span className="flex flex-col">
                <span className="text-sm text-heading">{user.name}</span>
                {user.plan && (
                  <span className="text-[11px] tracking-[0.05em] text-text-default">
                    {user.plan}
                  </span>
                )}
              </span>
            )}
          </div>
        </div>
      )}

      {onLogout && (
        <button
          type="button"
          className={`${LOGOUT_BASE} ${collapsed ? 'justify-center p-2' : 'px-4 py-2'}`}
          onClick={onLogout}
          title={collapsed ? '로그아웃' : undefined}
        >
          <img className="h-[18px] w-[18px]" src={logoutIcon} alt="" />
          {!collapsed && '로그아웃'}
        </button>
      )}
    </aside>
  );
}
