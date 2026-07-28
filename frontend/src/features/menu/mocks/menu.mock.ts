// 메뉴 트리 목 데이터 — MSW 핸들러(api/menuApi.handlers.ts) 전용.
// SideNavBar.tsx의 기존 DEFAULT_ITEMS/DEFAULT_ADMIN_ITEM 구조를 그대로 따른다(실제 공유 dev DB의
// 코드명과는 다를 수 있음 — 시드 데이터는 DB 담당자가 별도로 확정 중) — ADMIN role 기준(모든 메뉴 +
// 관리자 페이지 포함)으로, role별 필터링은 실제로는 백엔드가 하므로 이 목은 "필터링 이후" 트리 하나만
// 대표로 둔다.
import type { MenuTreeItem } from '../types';

export const mockMenuTree: MenuTreeItem[] = [
  {
    code: 'DASHBOARD',
    name: '대시보드',
    menuType: 'GROUP',
    iconKey: 'dashboard',
    path: null,
    activePathPattern: null,
    opensNewTab: false, enabled: true,
    children: [
      { code: 'DASHBOARD_OVERVIEW', name: '전체 시설물 현황', menuType: 'INTERNAL', iconKey: 'dashboard', path: '/dashboard', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'DASHBOARD_UPCOMING_INSPECTIONS', name: '다음 점검일 도래', menuType: 'INTERNAL', iconKey: 'dashboard', path: '/dashboard/upcoming-inspections', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'DASHBOARD_AI_WEEKLY_BRIEFING', name: 'AI 주간 브리핑 카드', menuType: 'INTERNAL', iconKey: 'dashboard', path: '/dashboard/ai-weekly-briefing', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
    ],
  },
  {
    code: 'FACILITIES',
    name: '시설물 관리',
    menuType: 'GROUP',
    iconKey: 'facilities',
    path: null,
    activePathPattern: null,
    opensNewTab: false, enabled: true,
    children: [
      { code: 'FACILITIES_LIST', name: '시설물 목록/등록', menuType: 'INTERNAL', iconKey: 'facilities', path: '/facilities/list', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'FACILITIES_INSPECTION_CYCLE', name: '점검 주기 설정', menuType: 'INTERNAL', iconKey: 'facilities', path: '/facilities/inspection-cycle', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'FACILITIES_MAP', name: '지도 뷰', menuType: 'INTERNAL', iconKey: 'facilities', path: '/facilities/map', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
    ],
  },
  {
    code: 'INSPECTIONS',
    name: '점검 관리',
    menuType: 'GROUP',
    iconKey: 'inspections',
    path: null,
    activePathPattern: null,
    opensNewTab: false, enabled: true,
    children: [
      { code: 'INSPECTIONS_CREATE', name: '점검(회차) 생성', menuType: 'INTERNAL', iconKey: 'inspections', path: '/inspections/create', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'INSPECTIONS_AI_ANALYSIS', name: 'AI 분석 실행/상태', menuType: 'INTERNAL', iconKey: 'inspections', path: '/inspections/create', activePathPattern: '/inspections/ai-analysis', opensNewTab: false, enabled: true, children: [] },
      { code: 'INSPECTIONS_RESULT_VIEWER', name: '분석 결과 뷰어', menuType: 'INTERNAL', iconKey: 'inspections', path: '/inspections/create', activePathPattern: '/inspections/1/viewer', opensNewTab: false, enabled: true, children: [] },
      { code: 'INSPECTIONS_REPORT_ENTRY', name: '보고서 생성 진입점', menuType: 'INTERNAL', iconKey: 'inspections', path: '/inspections/create', activePathPattern: '/inspections/1/reports', opensNewTab: false, enabled: true, children: [] },
    ],
  },
  { code: 'DEFECTS', name: '하자 관리', menuType: 'INTERNAL', iconKey: 'defects', path: '/defects/list', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
  {
    code: 'REPORTS',
    name: '보고서',
    menuType: 'GROUP',
    iconKey: 'reports',
    path: null,
    activePathPattern: null,
    opensNewTab: false, enabled: true,
    children: [
      { code: 'REPORTS_LIST', name: '보고서 목록/이력 관리', menuType: 'INTERNAL', iconKey: 'reports', path: '/reports', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'REPORTS_EDIT', name: '보고서 편집·미리보기', menuType: 'INTERNAL', iconKey: 'reports', path: '/reports', activePathPattern: '/reports/1', opensNewTab: false, enabled: true, children: [] },
      { code: 'REPORTS_EXPORT', name: 'PDF 내보내기', menuType: 'INTERNAL', iconKey: 'reports', path: '/reports', activePathPattern: '/reports/1', opensNewTab: false, enabled: true, children: [] },
    ],
  },
  {
    code: 'SUPPORT',
    name: '고객지원',
    menuType: 'GROUP',
    iconKey: 'support',
    path: null,
    activePathPattern: null,
    opensNewTab: false, enabled: true,
    children: [
      { code: 'SUPPORT_AI_ASSISTANT', name: 'AI 어시스턴트', menuType: 'INTERNAL', iconKey: 'support', path: '/support/ai-assistant', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'SUPPORT_CHAT_BOT', name: '상담 챗봇', menuType: 'INTERNAL', iconKey: 'support', path: '/support/chat-bot', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'SUPPORT_HISTORY', name: '내 상담 이력', menuType: 'INTERNAL', iconKey: 'support', path: '/support/history', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
    ],
  },
  {
    code: 'MYPAGE',
    name: '마이페이지',
    menuType: 'GROUP',
    iconKey: 'mypage',
    path: null,
    activePathPattern: null,
    opensNewTab: false, enabled: true,
    children: [
      { code: 'MYPAGE_PROFILE', name: '내 정보', menuType: 'INTERNAL', iconKey: 'mypage', path: '/mypage/profile', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'MYPAGE_INSPECTIONS', name: '내 점검 이력/보고서', menuType: 'INTERNAL', iconKey: 'mypage', path: '/mypage/inspections', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'MYPAGE_PLAN', name: '내 플랜', menuType: 'INTERNAL', iconKey: 'mypage', path: '/mypage/plan', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
    ],
  },
  { code: 'STATISTICS', name: '통계', menuType: 'INTERNAL', iconKey: 'statistics', path: '/statistics', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
  { code: 'SETTINGS', name: '설정', menuType: 'INTERNAL', iconKey: 'settings', path: '/settings', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
  {
    code: 'ADMIN',
    name: '관리자 페이지',
    menuType: 'GROUP',
    iconKey: 'admin',
    path: null,
    activePathPattern: null,
    opensNewTab: false, enabled: true,
    children: [
      { code: 'ADMIN_USERS', name: '사용자 관리', menuType: 'INTERNAL', iconKey: 'admin', path: '/admin/users', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
      { code: 'ADMIN_PLANS_QUOTA', name: '플랜·쿼터 관리', menuType: 'INTERNAL', iconKey: 'admin', path: '/admin/plans-quota', activePathPattern: null, opensNewTab: false, enabled: true, children: [] },
    ],
  },
];

// 관리자 페이지 그룹을 뺀 일반 role 기준 트리 — 실제로는 백엔드가 role별로 필터링해 내려주지만,
// 프론트 테스트에서 "ADMIN이 아닌 사용자에게는 ADMIN 그룹이 아예 오지 않는다"를 재현하려고 별도로 둔다.
export const mockMenuTreeWithoutAdmin: MenuTreeItem[] = mockMenuTree.filter((menu) => menu.code !== 'ADMIN');
