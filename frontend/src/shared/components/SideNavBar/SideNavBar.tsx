import { useEffect, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent, MouseEvent } from 'react';
import { Link } from 'react-router-dom';
import { useInspectionStore } from '../../../features/inspection/store/inspectionStore';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import brandIcon from '../../../assets/brand/sidenav-brand-icon.png';
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
import statisticsIcon from '../../../assets/brand/sidenav-statistics.svg';
import defaultAvatarIcon from '../../../assets/brand/sidenav-default-avatar.svg';

export interface SideNavSubItem {
  label: string;
  href: string;
  /** 라벨 텍스트 변경에 안전하게 특정 항목을 찾기 위한 안정 식별자(선택) — 동적 링크 생성 등에 사용 */
  id?: string;
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
  /** 상단 브랜드 로고 클릭 시 이동 대상. 미지정 시 기존 동작대로 '/dashboard'(#535 플랫폼 관리자
   * 콘솔처럼 일반 대시보드가 없는 셸에서 override) */
  brandHref?: string;
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

// 접힌 상태 고정 폭(w-18 = 18*4px) / 펼친 상태 기본 폭 — 드래그 리사이즈 clamp 범위(HAJA-167, #184)
const COLLAPSED_WIDTH = 72;
// MIN_WIDTH(및 DEFAULT_WIDTH)는 현재 메뉴 라벨 중 가장 긴 문자열("보고서 목록/이력 관리")이
// 2줄로 줄바꿈되지 않는 최소 너비를 실측(약 240px 경계 + 여유 4px)해 정한 값이다(#499).
// 라벨이 더 길어지면(관리자 메뉴 포함 실측 결과 현재는 이보다 짧음) 이 상수도 같이 검토할 것 —
// whitespace-nowrap/truncate로 자르는 대신 "2줄이 되기 전에 리사이즈를 멈추는" 방식을 택함(요청 사항).
const DEFAULT_WIDTH = 244;
const MIN_WIDTH = 244;
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
      { id: 'ai-analysis', label: 'AI 분석 실행/상태', href: '/inspections/ai-analysis' },
      { id: 'result-viewer', label: '분석 결과 뷰어', href: '/inspections/1/viewer' },
      { label: '보고서 생성 진입점', href: '/inspections/report-entry' },
    ],
  },
  // '하자 상세'는 목록에서 항목을 눌러 실제 id로 /defects/:id에 진입하는 방식이라 사이드바에
  // 별도 진입점이 필요 없다(#499, 사용자 요청) — 남은 하위 항목이 '하자 목록' 하나뿐이라
  // 통계/설정과 같이 하위메뉴 없는 단일 항목으로 정리.
  { label: '하자 관리', href: '/defects/list', icon: defectsIcon },
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
      // '내 점검 이력'과 '내 보고서'를 한 항목으로 통합(HAJA-167, #184, 사용자 요청).
      // /mypage/reports는 router.tsx·implementedRoutes.ts 어디에도 등록된 적 없는
      // placeholder였으므로(=/mypage/inspections와 마찬가지로 아직 실제 페이지 없음)
      // 통합으로 인한 실제 접근 경로 손실은 없다. 추후 보고서 화면을 실제로 구현할 때
      // 이 메뉴 구조를 다시 검토할 것.
      { label: '내 점검 이력/보고서', href: '/mypage/inspections' },
      { label: '내 플랜', href: '/mypage/plan' },
    ],
  },
  { label: '통계', href: '/statistics', icon: statisticsIcon },
  { label: '설정', href: '/settings', icon: settingsIcon },
];

// 기업 관리자(자기 회사 스코프) 메뉴 — 플랫폼 관리자(전사 스코프) 기능은 별도 메뉴로 분리 예정.
// 정리 전 7개 항목 원본 구성은 docs/design/admin-menu-structure-snapshot.md 참고
// (하자 유형·등급 관리/상담 관리/RAG 문서 관리/서비스 통계/시스템 모니터링은 구현된 적 없는
// placeholder였고, 플랫폼 관리자 메뉴 설계 시 스코프 재검토 대상).
const DEFAULT_ADMIN_ITEM: SideNavItem = {
  label: '관리자 페이지',
  href: '/admin',
  icon: adminIcon,
  subItems: [
    { label: '사용자 관리', href: '/admin/users' },
    { label: '플랜·쿼터 관리', href: '/admin/plans-quota' },
  ],
};

const LINK_BASE =
  'flex w-full items-center rounded-full border-none bg-none text-base font-medium text-text-default no-underline cursor-pointer hover:bg-surface-muted hover:text-primary';

// w-full: 다른 메뉴 링크(LINK_BASE)와 동일하게 전체 폭을 채운 뒤 justify-center로 가운데 정렬—
// 이전엔 w-fit이라 폭을 안 채워 접힘 상태에서 justify-center가 먹지 않고 왼쪽으로 치우쳐 보였다(#499).
const LOGOUT_BASE =
  'flex w-full items-center gap-3 whitespace-nowrap border-none bg-none text-sm font-medium text-[#3f3f46] cursor-pointer';

export function SideNavBar({
  items = DEFAULT_ITEMS,
  adminItem = DEFAULT_ADMIN_ITEM,
  isAdmin = false,
  brandHref = '/dashboard',
  activeHref,
  user,
  onLogout,
  defaultCollapsed = false,
  onCollapseToggle,
  onWidthChange,
  isRouteImplemented = () => true,
}: SideNavBarProps) {
  const activeInspectionId = useInspectionStore((state) => state.activeInspectionId);

  // isAdmin=true일 때 spread로 매 렌더 새 배열이 생기면 activeHref 동기화 effect가 매 렌더 재실행되어
  // 수동으로 펼친 다른 그룹이 즉시 스냅백되는 버그가 있었음(PR#154 리뷰 P1) — useMemo로 참조 안정화
  // ponytail: "점검 관리" 그룹의 "AI 분석 실행/상태"와 "분석 결과 뷰어" 링크를 activeInspectionId로 동적 생성
  const allItems = useMemo(() => {
    const dynamicItems = items.map((item) => {
      if (item.label === '점검 관리') {
        return {
          ...item,
          subItems: (item.subItems || []).map((sub) => {
            if (sub.id === 'ai-analysis') {
              return activeInspectionId
                ? { ...sub, href: `/inspections/${activeInspectionId}/analysis` }
                : sub;
            }
            if (sub.id === 'result-viewer') {
              return activeInspectionId
                ? { ...sub, href: `/inspections/${activeInspectionId}/viewer` }
                : sub;
            }
            return sub;
          }),
        };
      }
      return item;
    });
    return isAdmin ? [...dynamicItems, adminItem] : dynamicItems;
  }, [isAdmin, items, adminItem, activeInspectionId]);
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
    // 토글 클릭 시점엔 마우스가 사이드바 위에 올라가 있는 경우가 많아 hoverExpanded가
    // true로 남아있으면 접기를 눌러도 시각적으로 펼쳐진 채 유지된다. 토글은 항상 명시적
    // 액션이므로 hover 오버레이 상태를 초기화해 실제 collapsed 값을 그대로 반영시킨다
    // — 이후 마우스를 뗐다가 다시 올리면 hover-펼침은 정상 동작한다(#317 피드백).
    setHoverExpanded(false);
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
    // 이전 드래그의 mouseup이 브라우저 창 밖에서 발생하는 등의 이유로 누락되면 window에
    // 등록된 이전 mousemove/mouseup 리스너가 정리되지 않은 채 남을 수 있다. 새 드래그를
    // 시작하기 전 항상 먼저 정리해 리스너 중복 등록을 방지한다(PR머신 리뷰 P2).
    stopResize();
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

  // 마우스 없이도 리사이즈 핸들에 포커스한 뒤 좌/우 화살표 키로 폭을 조절할 수 있게 한다
  // (WAI-ARIA separator 패턴 — PR머신 리뷰 P2, 키보드 접근성 회귀 지적 반영)
  function handleResizeKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const step = 16;
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      const nextWidth = clamp(width - step, MIN_WIDTH, MAX_WIDTH);
      setWidth(nextWidth);
      onWidthChange?.(nextWidth);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      const nextWidth = clamp(width + step, MIN_WIDTH, MAX_WIDTH);
      setWidth(nextWidth);
      onWidthChange?.(nextWidth);
    }
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
      // 바깥 wrapper(레이아웃 공간을 실제로 차지)가 안쪽 aside(시각적 박스)와 같은 트랜지션 없이
      // 너비를 즉시 바꾸면, aside가 아직 줄어드는 중인 동안 본문(main)이 이미 넓어진 자리를
      // 차지해버려 서로 겹쳐 보이는 버그가 있었다 — 동일한 transition으로 동기화(#499).
      className="relative flex-shrink-0 transition-[width] duration-150"
      style={{ width: collapsed ? COLLAPSED_WIDTH : width }}
      onMouseEnter={() => setHoverExpanded(true)}
      onMouseLeave={() => setHoverExpanded(false)}
    >
      <aside
        // overflow-hidden: 펼침 애니메이션 도중(너비가 아직 좁을 때) 줄바꿈을 막은 라벨 텍스트가
        // 옆으로 넘치더라도 본문(main) 위로 삐져나오지 않고 잘리게 한다(#499 펼침 애니메이션 정리).
        className={`flex min-h-full flex-col overflow-hidden border-r border-border bg-surface text-sm transition-[width] duration-150 ${
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
          <Link
            to={brandHref}
            onClick={(event) => handleNavClick(event, brandHref)}
            className={`flex w-full items-center gap-1.5 no-underline ${
              visuallyExpanded ? '' : 'justify-center'
            }`}
            // brandHref override(#535 플랫폼 관리자 콘솔)에서도 문구가 어긋나지 않도록 "대시보드"
            // 특정 문구 대신 범용 문구를 쓴다.
            aria-label="HajaCheck 홈으로 이동"
          >
            {visuallyExpanded ? (
              <img className="h-7 w-auto object-contain" src={brandLogo} alt="HajaCheck" />
            ) : (
              <img className="h-8 w-8 object-contain" src={brandIcon} alt="HajaCheck" />
            )}
            {visuallyExpanded && isAdmin && (
              <span className="rounded-full border border-[#e5e7eb] bg-[#f3f4f6] px-[7px] py-[3px] text-[10px] tracking-[0.05em] text-[#6b7280]">
                ADMIN
              </span>
            )}
          </Link>
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

        <nav className="flex flex-col gap-2 pt-1" aria-label="사이드 메뉴">
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
                    className={`inline-flex items-center whitespace-nowrap ${visuallyExpanded ? 'gap-3' : 'gap-0'}`}
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
                        className={`whitespace-nowrap rounded-full px-4 py-[6px] text-[13px] no-underline hover:text-primary ${
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
                  className={`inline-flex items-center whitespace-nowrap ${visuallyExpanded ? 'gap-3' : 'gap-0'}`}
                >
                  <img className="h-[18px] w-[18px] object-contain" src={item.icon} alt="" />
                  {visuallyExpanded && item.label}
                </span>
              </Link>
            ),
          )}
        </nav>

        {/* 메뉴 항목 수와 무관하게 프로필·로그아웃을 사이드바 맨 아래에 고정한다 — 예전엔 nav 자체가
            flex-1이라 항목이 적을 때(관리자 메뉴 축소 등) 아코디언 바로 아래에 거대한 빈 공간이
            생겼다(#525 팔로우업). 남는 공간은 nav가 아니라 이 스페이서가 흡수한다. */}
        <div className="flex-1" aria-hidden="true" />

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
          <div
            // 접힘 상태에서도 px-4를 그대로 쓰면(72px 폭 - aside 자체 padding까지 겹쳐) 아바타(32px)가
            // 들어갈 공간이 부족해 flex-shrink로 폭만 눌려 타원으로 보였다 — 다른 nav 항목처럼
            // 접힘 상태 전용 패딩(px-2)으로 분기(#499).
            className={`border-t border-[#cbc4d2]/30 pt-[17px] pb-2.5 ${visuallyExpanded ? 'px-4' : 'px-2'}`}
          >
            <div className={`flex items-center gap-2 ${visuallyExpanded ? '' : 'justify-center'}`}>
              {user.avatarUrl ? (
                <img
                  className="h-8 w-8 flex-shrink-0 rounded-full bg-[#cbc4d2] object-cover"
                  src={user.avatarUrl}
                  alt=""
                />
              ) : (
                // 사진 없을 때 빈 원이 아니라 기본 아이콘(건물 모양)을 넣는다 — Figma node 163-663 기준(#499)
                <span
                  className="inline-flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-[#cbc4d2]"
                  aria-hidden="true"
                >
                  <img className="h-[27px] w-[27px] object-contain" src={defaultAvatarIcon} alt="" />
                </span>
              )}
              {visuallyExpanded && (
                <span className="flex flex-col overflow-hidden">
                  <span className="truncate text-sm text-heading">{user.name}</span>
                  {user.plan && (
                    <span className="truncate text-[11px] tracking-[0.05em] text-text-default">
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
            aria-valuenow={width}
            aria-valuemin={MIN_WIDTH}
            aria-valuemax={MAX_WIDTH}
            tabIndex={0}
            onMouseDown={handleResizeMouseDown}
            onKeyDown={handleResizeKeyDown}
            className="absolute right-0 top-0 h-full w-1 cursor-col-resize hover:bg-primary/30 focus-visible:bg-primary/40 focus-visible:outline-none"
          />
        )}
      </aside>
    </div>
  );
}
