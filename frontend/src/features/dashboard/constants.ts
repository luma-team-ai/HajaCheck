// 사이드바 네비게이션 구성 — dev-03-01(Figma) 기준
// 실제 구현된 경로는 /dashboard(전체 시설물 현황)뿐 — 나머지는 담당 feature 착수 전까지 비활성 처리

// 스토리보드 DASH-01 action 이동 경로 (URL 하드코딩 방지 — 단일 지점 관리)
// A1: 새 점검 시작 → 점검 회차 생성(INSP-01, FR-2-01 업로드)
export const INSPECTION_NEW_PATH = '/inspections/new';
// A2: 검수하기 → 분석 결과 뷰어에서 수동 검수(INSP-04, FR-4-02)
export const inspectionReviewPath = (inspectionId: number): string =>
  `/inspections/${inspectionId}/viewer`;

export interface DashboardNavSubItem {
  label: string;
  path: string;
  isActive: boolean;
}

// Figma dev-03-01 기준 메뉴별 아이콘 — Sidebar.tsx의 NavIcon 컴포넌트가 이 이름으로 SVG를 렌더링
export type DashboardNavIconName =
  | 'dashboard'
  | 'facility'
  | 'inspection'
  | 'defect'
  | 'report'
  | 'support'
  | 'mypage'
  | 'settings';

export interface DashboardNavItem {
  label: string;
  icon: DashboardNavIconName;
  subItems?: DashboardNavSubItem[];
}

export const NAV_ITEMS: DashboardNavItem[] = [
  {
    label: '대시보드',
    icon: 'dashboard',
    subItems: [
      { label: '전체 시설물 현황', path: '/dashboard', isActive: true },
      { label: '다음 점검일 도래', path: '#', isActive: false },
      { label: 'AI 주간 브리핑', path: '#', isActive: false },
    ],
  },
  { label: '시설물 관리', icon: 'facility' },
  { label: '점검 관리', icon: 'inspection' },
  { label: '하자 관리', icon: 'defect' },
  { label: '보고서', icon: 'report' },
  { label: '고객지원', icon: 'support' },
  { label: '마이페이지', icon: 'mypage' },
  { label: '설정', icon: 'settings' },
];
