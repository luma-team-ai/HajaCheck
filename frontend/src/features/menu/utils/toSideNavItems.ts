import type { SideNavItem, SideNavSubItem } from '../../../shared/components/SideNavBar';
import type { MenuTreeItem } from '../types';
import { DEFAULT_MENU_ICON, MENU_ICON_MAP } from './menuIcons';

// SideNavBar.tsx의 activeInspectionId 후처리(allItems useMemo)는 sub.id 값으로 대상 서브 항목을
// 식별한다(점검 관리/보고서 그룹) — 백엔드는 안정적인 code만 내려주므로, 그 후처리가 백엔드 응답에도
// 그대로 동작하도록 이 코드들만 SideNavBar가 원래 쓰던 로컬 id로 옮겨 담는다. 그 외 서브 항목은
// id 없이도 href만으로 활성 판정이 되므로 매핑하지 않는다(#1003).
// 코드값은 현재 공유 dev DB에 실제로 들어있는 시드 기준(2026-07-25, 담당자 시드)이다 — 최초 작성 시
// 추정했던 코드명(REPORTS_EDIT/REPORTS_EXPORT/SUPPORT_CHAT_BOT)과 실제 값이 달라 매칭이 안 됐던 걸
// 바로잡았다. 'INSPECTIONS_REPORT_ENTRANCE'(점검 요약 및 보고서 생성)는 시드된 path가
// `/inspections/report-entrance`라는 실재하지 않는 정적 경로라, 매핑 없이는 분석 중인 점검이 있어도
// isRouteImplemented가 항상 미구현으로 판정해 클릭이 막혔다(#1088). AI 분석/결과뷰어와 동일하게
// activeInspectionId 유무로 /inspections/{id}/reports ↔ /inspections/create를 오가도록 매핑한다.
const DYNAMIC_SUB_ITEM_ID_BY_CODE: Record<string, string> = {
  INSPECTIONS_AI_ANALYSIS: 'ai-analysis',
  INSPECTIONS_RESULT_VIEWER: 'result-viewer',
  INSPECTIONS_REPORT_ENTRANCE: 'report-entry',
  INSPECTIONS_REPORT_ENTRY: 'report-entry',
  REPORTS_LIST: 'report-list',
  REPORTS_EDITOR: 'report-edit',
  REPORTS_EDIT: 'report-edit',
  REPORTS_EXPORT_PDF: 'report-export',
  REPORTS_EXPORT: 'report-export',
};

// router.tsx가 실제 :id와 무관하게 보고하는 정적 activeHref(#368)와 맞춘 고정값 — 백엔드
// menus 시드의 activePathPattern이 비어 있어도(null) 이 값으로 폴백해 항상 하이라이트가 끊기지
// 않게 한다. 이 세 코드가 없으면 activePathPattern이 null인 시드에서 matchHref가 undefined로
// 빠지고, href(=activeInspectionId 기반 실제 id 경로)와 router의 정적 activeHref가 영영 일치하지
// 않아 AI 분석/결과 뷰어/보고서 생성 세 메뉴만 선택 표시가 안 되는 버그가 생긴다.
const DYNAMIC_MATCH_HREF_BY_ID: Record<string, string> = {
  'ai-analysis': '/inspections/ai-analysis',
  'result-viewer': '/inspections/1/viewer',
  'report-entry': '/inspections/1/reports',
  'report-edit': '/reports/1',
  'report-export': '/reports/1?mode=export',
};

const LOCALLY_IMPLEMENTED_MENU_CODES = new Set([
  'INSPECTIONS_REPORT_ENTRANCE',
  'INSPECTIONS_REPORT_ENTRY',
  'REPORTS_LIST',
  'REPORTS_EDITOR',
  'REPORTS_EDIT',
  'REPORTS_EXPORT_PDF',
  'REPORTS_EXPORT',
]);

// 관리자 페이지(DEFAULT_ADMIN_ITEM)는 최상위 트리와 분리된 별도 prop(SideNavBar의 adminItem)으로
// 전달돼야 isAdmin일 때만 노출되는 기존 동작이 재현된다. code가 아니라 표시 라벨로 식별하는 이유:
// menus 시드는 DB 담당자가 별도로 확정 중이라 최상위 관리자 그룹의 code 값(예: 'ADMIN' vs
// 'ADMIN_CONSOLE')이 아직 안정적이지 않다 — code로 매칭하면 실제 값이 다를 때 조용히 매칭 실패해
// DB의 진짜 관리자 그룹이 adminItem이 아니라 일반 items에 섞여 들어가고, 동시에 SideNavBar 자체
// 기본값(DEFAULT_ADMIN_ITEM)까지 겹쳐 붙어 "관리자 페이지"가 2개로 보이는 사고가 있었다. 라벨
// "관리자 페이지"는 SideNavBar의 다른 label 기반 매칭(예: '점검 관리')과 동일한 관례라 code 명명이
// 바뀌어도 안전하다.
const ADMIN_GROUP_LABEL = '관리자 페이지';

function resolveIcon(iconKey: string | null): string {
  if (!iconKey) {
    return DEFAULT_MENU_ICON;
  }
  return MENU_ICON_MAP[iconKey] ?? DEFAULT_MENU_ICON;
}

function toSubItem(menu: MenuTreeItem): SideNavSubItem {
  const id = DYNAMIC_SUB_ITEM_ID_BY_CODE[menu.code];
  return {
    label: menu.name,
    href: menu.path ?? '#',
    id,
    matchHref: (id ? DYNAMIC_MATCH_HREF_BY_ID[id] : undefined) ?? menu.activePathPattern ?? undefined,
    enabled: LOCALLY_IMPLEMENTED_MENU_CODES.has(menu.code) ? true : menu.enabled,
  };
}

function toItem(menu: MenuTreeItem): SideNavItem {
  return {
    label: menu.name,
    // GROUP은 DB상 path가 항상 null이다(menus.ck_menus_path_by_type) — SideNavBar가 GROUP 항목을
    // 실제로 네비게이션에 쓰지 않고 React key로만 쓰므로, 링크로 오인되지 않는 값이면 충분하다.
    href: menu.path ?? `#${menu.code}`,
    icon: resolveIcon(menu.iconKey),
    subItems: menu.children.length > 0 ? menu.children.map(toSubItem) : undefined,
    // GROUP 자체는 토글 버튼이라(handleNavClick을 안 탐) enabled가 의미 없지만, 리프는 그대로 전달해
    // is_enabled=false인 메뉴가 표시는 되되 클릭이 막히도록 한다(#1003 팔로우업).
    enabled: menu.enabled,
  };
}

export interface SideNavItemsFromTree {
  items: SideNavItem[];
  adminItem?: SideNavItem;
}

// GET /api/menus 응답(role로 이미 필터링됨)을 SideNavBar props 형태로 변환한다(#1003).
export function toSideNavItems(tree: MenuTreeItem[]): SideNavItemsFromTree {
  const adminMenu = tree.find((menu) => menu.menuType === 'GROUP' && menu.name === ADMIN_GROUP_LABEL);
  const items = tree.filter((menu) => menu !== adminMenu).map(toItem);
  return { items, adminItem: adminMenu ? toItem(adminMenu) : undefined };
}
