import { describe, expect, it } from 'vitest';
import type { MenuTreeItem } from '../types';
import { toSideNavItems } from './toSideNavItems';

function leaf(overrides: Partial<MenuTreeItem> & Pick<MenuTreeItem, 'code' | 'name'>): MenuTreeItem {
  return {
    menuType: 'INTERNAL',
    iconKey: 'dashboard',
    path: '/some-path',
    activePathPattern: null,
    opensNewTab: false,
    enabled: true,
    children: [],
    ...overrides,
  };
}

function group(overrides: Partial<MenuTreeItem> & Pick<MenuTreeItem, 'code' | 'name' | 'children'>): MenuTreeItem {
  return {
    menuType: 'GROUP',
    iconKey: 'dashboard',
    path: null,
    activePathPattern: null,
    opensNewTab: false,
    enabled: true,
    ...overrides,
  };
}

describe('toSideNavItems', () => {
  it('code=ADMIN 그룹을 adminItem으로 분리하고, 나머지는 items에 담는다', () => {
    const tree: MenuTreeItem[] = [
      leaf({ code: 'STATISTICS', name: '통계' }),
      group({
        code: 'ADMIN',
        name: '관리자 페이지',
        children: [leaf({ code: 'ADMIN_USERS', name: '사용자 관리', path: '/admin/users' })],
      }),
    ];

    const { items, adminItem } = toSideNavItems(tree);

    expect(items).toHaveLength(1);
    expect(items[0].label).toBe('통계');
    expect(adminItem?.label).toBe('관리자 페이지');
    expect(adminItem?.subItems).toHaveLength(1);
    expect(adminItem?.subItems?.[0].label).toBe('사용자 관리');
  });

  it('ADMIN 그룹이 응답에 없으면 adminItem은 undefined다(ADMIN이 아닌 role)', () => {
    const tree: MenuTreeItem[] = [leaf({ code: 'STATISTICS', name: '통계' })];

    const { adminItem } = toSideNavItems(tree);

    expect(adminItem).toBeUndefined();
  });

  it('activeInspectionId 동적 후처리 대상 코드는 SideNavBar가 기대하는 로컬 id로 매핑된다', () => {
    const tree: MenuTreeItem[] = [
      group({
        code: 'INSPECTIONS',
        name: '점검 관리',
        children: [
          leaf({
            code: 'INSPECTIONS_AI_ANALYSIS',
            name: 'AI 분석 실행/상태',
            path: '/inspections/create',
            activePathPattern: '/inspections/ai-analysis',
          }),
        ],
      }),
    ];

    const { items } = toSideNavItems(tree);

    const sub = items[0].subItems?.[0];
    expect(sub?.id).toBe('ai-analysis');
    expect(sub?.href).toBe('/inspections/create');
    expect(sub?.matchHref).toBe('/inspections/ai-analysis');
  });

  it('매핑 대상이 아닌 서브 항목은 id가 없다', () => {
    const tree: MenuTreeItem[] = [
      group({
        code: 'DASHBOARD',
        name: '대시보드',
        children: [leaf({ code: 'DASHBOARD_OVERVIEW', name: '전체 시설물 현황', path: '/dashboard' })],
      }),
    ];

    const { items } = toSideNavItems(tree);

    expect(items[0].subItems?.[0].id).toBeUndefined();
  });

  it('REPORTS_EDITOR/REPORTS_EXPORT_PDF 및 구 목 코드도 로컬 id로 매핑된다', () => {
    const tree: MenuTreeItem[] = [
      group({
        code: 'REPORTS',
        name: '보고서',
        children: [
          leaf({ code: 'REPORTS_EDITOR', name: '보고서 편집·미리보기', path: '/reports' }),
          leaf({ code: 'REPORTS_EXPORT_PDF', name: 'PDF 내보내기', path: '/reports' }),
          leaf({ code: 'REPORTS_EDIT', name: '보고서 편집·미리보기', path: '/reports' }),
          leaf({ code: 'REPORTS_EXPORT', name: 'PDF 내보내기', path: '/reports' }),
        ],
      }),
    ];

    const { items } = toSideNavItems(tree);

    expect(items[0].subItems?.[0].id).toBe('report-edit');
    expect(items[0].subItems?.[1].id).toBe('report-export');
    expect(items[0].subItems?.[2].id).toBe('report-edit');
    expect(items[0].subItems?.[3].id).toBe('report-export');
  });

  it('INSPECTIONS_REPORT_ENTRANCE도 로컬 id(report-entry)로 매핑된다 — 실재하지 않는 시드 path(/inspections/report-entrance)에 갇히지 않고 activeInspectionId 기반 동적 href로 대체되도록 한다(#1088)', () => {
    const tree: MenuTreeItem[] = [
      group({
        code: 'INSPECTIONS',
        name: '점검 관리',
        children: [
          leaf({
            code: 'INSPECTIONS_REPORT_ENTRANCE',
            name: '점검 요약 및 보고서 생성',
            path: '/inspections/report-entrance',
          }),
        ],
      }),
    ];

    const { items } = toSideNavItems(tree);

    expect(items[0].subItems?.[0].id).toBe('report-entry');
  });

  it('INSPECTIONS_REPORT_ENTRY 구 목 코드도 로컬 id(report-entry)로 매핑된다', () => {
    const tree: MenuTreeItem[] = [
      group({
        code: 'INSPECTIONS',
        name: '점검 관리',
        children: [
          leaf({
            code: 'INSPECTIONS_REPORT_ENTRY',
            name: '보고서 생성 진입점',
            path: '/inspections/create',
          }),
        ],
      }),
    ];

    const { items } = toSideNavItems(tree);

    expect(items[0].subItems?.[0].id).toBe('report-entry');
  });

  it('menu.enabled가 false면 SideNavItem/SideNavSubItem에도 그대로 전달된다', () => {
    const tree: MenuTreeItem[] = [
      leaf({ code: 'STATISTICS', name: '통계', enabled: false }),
      group({
        code: 'SUPPORT',
        name: '고객지원',
        children: [leaf({ code: 'SUPPORT_HISTORY', name: '내 상담 이력', path: '/support/history', enabled: false })],
      }),
    ];

    const { items } = toSideNavItems(tree);

    expect(items[0].enabled).toBe(false);
    expect(items[1].subItems?.[0].enabled).toBe(false);
  });

  it('이미 구현된 보고서 메뉴는 DB seed의 stale enabled=false에 막히지 않도록 활성화한다', () => {
    const tree: MenuTreeItem[] = [
      group({
        code: 'INSPECTIONS',
        name: '점검 관리',
        children: [
          leaf({
            code: 'INSPECTIONS_REPORT_ENTRANCE',
            name: '점검 요약 및 보고서 생성',
            path: '/inspections/report-entrance',
            enabled: false,
          }),
        ],
      }),
      group({
        code: 'REPORTS',
        name: '보고서',
        children: [
          leaf({ code: 'REPORTS_LIST', name: '보고서 목록', path: '/reports', enabled: false }),
          leaf({ code: 'REPORTS_EXPORT_PDF', name: 'PDF 내보내기', path: '/reports', enabled: false }),
        ],
      }),
    ];

    const { items } = toSideNavItems(tree);

    expect(items[0].subItems?.[0].enabled).toBe(true);
    expect(items[1].subItems?.[0].enabled).toBe(true);
    expect(items[1].subItems?.[1].enabled).toBe(true);
  });

  it('GROUP은 하위메뉴가 없으면 트리에서 제외돼 있다는 전제(백엔드 보장) 하에 children이 비어있는 GROUP도 안전하게 변환한다', () => {
    const tree: MenuTreeItem[] = [group({ code: 'DASHBOARD', name: '대시보드', children: [] })];

    const { items } = toSideNavItems(tree);

    expect(items[0].subItems).toBeUndefined();
    expect(items[0].href).toBe('#DASHBOARD');
  });

  it('icon_key가 매핑에 없으면 기본 아이콘으로 폴백한다', () => {
    const tree: MenuTreeItem[] = [leaf({ code: 'UNKNOWN', name: '알수없음', iconKey: 'not-mapped' })];

    const { items } = toSideNavItems(tree);

    expect(items[0].icon).toBeTruthy();
  });
});
