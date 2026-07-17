import { useEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent } from 'react';
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
  /** 펼쳐진 상태에서 드래그로 폭을 조절할 때마다 호출(옵션 — 호출부가 폭 변화에 반응할 필요 없으면 생략 가능) */
  onWidthChange?: (width: number) => void;
  /**
   * href가 실제로 이동 가능한 라우트인지 판별. SideNavBar는 라우터 전체 구조를 모르므로
   * 호출부(AppLayout)가 주입 — 미지정 시 전부 구현된 것으로 간주(기존 동작과 동일).
   * false를 반환하는 항목을 클릭하면 이동을 막고 안내 메시지를 띄운다.
   */
  isRouteImplemented?: (href: string) => boolean;
}

const NOTICE_AUTO_DISMISS_MS = 2500;
const NOT_IMPLEMENTED_MESSAGE = '아직 구현되지 않은 페이지입니다';

// 접힌 상태 고정 폭(w-18 = 18*4px) / 펼친 상태 기본 폭(w-60 = 60*4px) — 드래그 리사이즈 clamp 범위(HAJA-167, #184)
const COLLAPSED_WIDTH = 72;
const DEFAULT_WIDTH = 240;
const MIN_WIDTH = 200;
const MAX_WIDTH = 320;

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

// Figma node-id 151-967 "대시보드SideNavBar"(일반) / 163-663 "관리자 SideNavBar"(관리자) 기준
const DEFAULT_ITEMS: SideNavItem[] = [
  {
    label: '대시보드',
    href: '/dashboard',
    icon: dashboardIcon,
    subItems: [
      { label: '전체 시설물 현황', href: '/dashboard' },
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
      { label: '분석 결과 뷰어', href: '/inspections/1/viewer' },
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
    href: '/mypage',
    icon: mypageIcon,
    subItems: [
      { label: '내 정보', href: '/mypage/profile' },
      { label: '내 점검 이력/보고서', href: '/mypage/inspections' },
      { label: '내 플랜', href: '/mypage/plan' },
      { label: '내 상담 내역', href: '/mypage/counsels' },
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
  onWidthChange,
  isRouteImplemented = () => true,
}: SideNavBarProps) {
  // isAdmin=true일 때 spread로 매 렌더 새 배열이 생기면 activeHref 동기화 effect가 매 렌더 재실행되어
  // 수동으로 펼친 다른 그룹이 즉시 스냅백되는 버그가 있었음(PR#154 리뷰 P1) — useMemo로 참조 안정화
  const allItems = useMemo(
    () => (isAdmin ? [...items, adminItem] : items),
    [isAdmin, items, adminItem],
  );
  const [expandedLabel, setExpandedLabel] = useState<string | undefined>(() =>
    allItems.find((item) => item.subItems?.some((sub) => sub.href === activeHref))?.label,
  );
  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  // 접힌 상태에서 마우스를 올렸을 때 시각적으로만 펼쳐 보이는 오버레이 트리거 — 실제 collapsed
  // 상태(및 onCollapseToggle에 전달되는 값)는 바꾸지 않는다(HAJA-167, #184)
  const [hoverExpanded, setHoverExpanded] = useState(false);
  // 펼쳐진 상태에서 드래그로 조절하는 사이드바 폭(px) — 접힌 상태에서 hover 오버레이가 펼쳐질 때도
  // 동일한 폭을 재사용한다(HAJA-167, #184)
  const [width, setWidth] = useState(DEFAULT_WIDTH);
  // 미구현 라우트 클릭 시 안내 메시지 — 몇 초 후 자동으로 사라짐(HAJA-186, #217 후속)
  const [notice, setNotice] = useState<string | null>(null);
  const noticeTimerRef = useRef<ReturnType<typeof setTimeout>>();
  const dragStateRef = useRef<{ startX: number; startWidth: number } | null>(null);
  const dragMoveHandlerRef = useRef<((event: WindowEventMap['mousemove']) => void) | undefined>();
  const dragUpHandlerRef = useRef<(() => void) | undefined>();

  // 실제로 접혀 있든, 접힌 채 hover로 시각적으로만 펼쳐져 있든 라벨/아코디언/하위메뉴는 동일하게
  // 보여준다 — 실제 collapsed 상태와 분리된 "보이는 상태"(HAJA-167, #184)
  const visuallyExpanded = !collapsed || hoverExpanded;
  const isOverlay = collapsed && hoverExpanded;

  // activeHref가 마운트 이후 바뀌어도(사이드바 클릭이 아닌 다른 경로로 하위 라우트 진입 시) 해당 그룹이 펼쳐지도록 동기화
  useEffect(() => {
    const activeGroupLabel = allItems.find((item) =>
      item.subItems?.some((sub) => sub.href === activeHref),
    )?.label;
    if (activeGroupLabel) {
      setExpandedLabel(activeGroupLabel);
    }
  }, [activeHref, allItems]);

  // 언마운트 시 대기 중인 자동 dismiss 타이머 + 드래그 리사이즈 중이던 window 리스너 정리
  useEffect(() => {
    return () => {
      clearTimeout(noticeTimerRef.current);
      stopResize();
    };
  }, []);

  function toggleExpand(label: string) {
    setExpandedLabel((current) => (current === label ? undefined : label));
  }

  function handleNavClick(event: MouseEvent<HTMLAnchorElement>, href: string) {
    if (isRouteImplemented(href)) {
      return;
    }
    event.preventDefault();
    clearTimeout(noticeTimerRef.current);
    setNotice(NOT_IMPLEMENTED_MESSAGE);
    noticeTimerRef.current = setTimeout(() => setNotice(null), NOTICE_AUTO_DISMISS_MS);
  }

  function handleCollapseToggle() {
    setCollapsed((current) => {
      const next = !current;
      onCollapseToggle?.(next);
      return next;
    });
  }

  function stopResize() {
    if (dragMoveHandlerRef.current) {
      window.removeEventListener('mousemove', dragMoveHandlerRef.current);
    }
    if (dragUpHandlerRef.current) {
      window.removeEventListener('mouseup', dragUpHandlerRef.current);
    }
    dragMoveHandlerRef.current = undefined;
    dragUpHandlerRef.current = undefined;
    dragStateRef.current = null;
  }

  // 펼쳐진 상태(collapsed=false)에서만 렌더링되는 우측 가장자리 드래그 핸들의 mousedown 시작점.
  // 시작 X좌표/시작폭을 기록한 뒤 window에 mousemove/mouseup을 등록하고, mouseup(또는 언마운트) 시 정리한다(HAJA-167, #184)
  function handleResizeMouseDown(event: MouseEvent<HTMLDivElement>) {
    event.preventDefault();
    dragStateRef.current = { startX: event.clientX, startWidth: width };

    function handleMouseMove(moveEvent: WindowEventMap['mousemove']) {
      const dragState = dragStateRef.current;
      if (!dragState) {
        return;
      }
      const nextWidth = clamp(
        dragState.startWidth + (moveEvent.clientX - dragState.startX),
        MIN_WIDTH,
        MAX_WIDTH,
      );
      setWidth(nextWidth);
      onWidthChange?.(nextWidth);
    }

    function handleMouseUp() {
      stopResize();
    }

    dragMoveHandlerRef.current = handleMouseMove;
    dragUpHandlerRef.current = handleMouseUp;
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
  }

  function getLinkClassName(isActive: boolean, isGroup = false) {
    const active = isActive ? ' bg-surface text-primary ring-1 ring-border' : '';
    const layout = !visuallyExpanded
      ? 'justify-center p-2'
      : `${isGroup ? 'justify-between ' : ''}px-4 py-2`;
    return `${LINK_BASE} ${layout}${active}`;
  }

  return (
    <div
      className="relative flex-shrink-0"
      style={{ width: collapsed ? COLLAPSED_WIDTH : width }}
      onMouseEnter={() => setHoverExpanded(true)}
      onMouseLeave={() => setHoverExpanded(false)}
    >
      <aside
        className={`flex min-h-full flex-col border-r border-border bg-surface text-sm transition-[width] duration-150 ${
          isOverlay ? 'absolute inset-y-0 left-0 z-50 shadow-lg' : 'relative'
        } ${visuallyExpanded ? 'p-4' : 'px-2 py-4'}`}
        style={{ width: visuallyExpanded ? width : COLLAPSED_WIDTH }}
      >
        <div
          className={`flex border-b border-border pb-4 mb-1 ${
            visuallyExpanded
              ? 'items-center justify-between gap-2 h-16'
              : 'flex-col items-center h-auto justify-center gap-3'
          }`}
        >
          <div
            className={`flex items-center gap-1.5 text-sm font-semibold text-primary ${
              visuallyExpanded ? '' : 'justify-center'
            }`}
          >
            <img className="h-4 w-4 object-contain" src={brandMark} alt="" />
            {visuallyExpanded && (
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
                  aria-expanded={visuallyExpanded && expandedLabel === item.label}
                  title={!visuallyExpanded ? item.label : undefined}
                >
                  <span
                    className={`inline-flex items-center ${visuallyExpanded ? 'gap-3' : 'gap-0'}`}
                  >
                    <img className="h-[18px] w-[18px] object-contain" src={item.icon} alt="" />
                    {visuallyExpanded && item.label}
                  </span>
                  {visuallyExpanded && (
                    <img
                      className={`h-[5.55px] w-[9px] transition-transform duration-150 ${
                        expandedLabel === item.label ? 'rotate-180' : ''
                      }`}
                      src={chevronIcon}
                      alt=""
                    />
                  )}
                </button>
                {visuallyExpanded && expandedLabel === item.label && (
                  <div className="flex flex-col gap-1 pr-4 pl-[46px]">
                    {item.subItems.map((sub) => (
                      <Link
                        key={sub.href}
                        to={sub.href}
                        onClick={(event) => handleNavClick(event, sub.href)}
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
                onClick={(event) => handleNavClick(event, item.href)}
                className={getLinkClassName(item.href === activeHref)}
                aria-current={item.href === activeHref ? 'page' : undefined}
                title={!visuallyExpanded ? item.label : undefined}
              >
                <span
                  className={`inline-flex items-center ${visuallyExpanded ? 'gap-3' : 'gap-0'}`}
                >
                  <img className="h-[18px] w-[18px] object-contain" src={item.icon} alt="" />
                  {visuallyExpanded && item.label}
                </span>
              </Link>
            ),
          )}
        </nav>

        {notice && (
          <div className="pointer-events-none fixed inset-0 z-[1000] flex items-center justify-center">
            <div
              role="status"
              aria-live="polite"
              className="rounded-[20px] border border-border bg-white/90 px-6 py-4 text-sm font-medium text-text-default shadow-[0px_20px_25px_-5px_rgba(0,0,0,0.1),0px_8px_10px_-6px_rgba(0,0,0,0.1)] backdrop-blur-[10px]"
            >
              {notice}
            </div>
          </div>
        )}

        {user && (
          <div className="border-t border-[#cbc4d2]/30 px-4 pt-[17px] pb-2.5">
            <div className={`flex items-center gap-2 ${visuallyExpanded ? '' : 'justify-center'}`}>
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
              {visuallyExpanded && (
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
            className={`${LOGOUT_BASE} ${visuallyExpanded ? 'px-4 py-2' : 'justify-center p-2'}`}
            onClick={onLogout}
            title={!visuallyExpanded ? '로그아웃' : undefined}
          >
            <img className="h-[18px] w-[18px]" src={logoutIcon} alt="" />
            {visuallyExpanded && '로그아웃'}
          </button>
        )}

        {!collapsed && (
          <div
            role="separator"
            aria-orientation="vertical"
            aria-label="사이드바 너비 조절"
            onMouseDown={handleResizeMouseDown}
            className="absolute right-0 top-0 h-full w-1 cursor-col-resize hover:bg-primary/30"
          />
        )}
      </aside>
    </div>
  );
}
