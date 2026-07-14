// 사이드바 네비게이션 구성 — dev-03-01(Figma) 기준
// 실제 구현된 경로는 /dashboard(전체 시설물 현황)뿐 — 나머지는 담당 feature 착수 전까지 비활성 처리

export interface DashboardNavSubItem {
  label: string;
  path: string;
  isActive: boolean;
}

export interface DashboardNavItem {
  label: string;
  subItems?: DashboardNavSubItem[];
}

export const NAV_ITEMS: DashboardNavItem[] = [
  {
    label: '대시보드',
    subItems: [
      { label: '전체 시설물 현황', path: '/dashboard', isActive: true },
      { label: '다음 점검일 도래', path: '#', isActive: false },
      { label: 'AI 주간 브리핑', path: '#', isActive: false },
    ],
  },
  { label: '시설물 관리' },
  { label: '점검 관리' },
  { label: '하자 관리' },
  { label: '보고서' },
  { label: '고객지원' },
  { label: '마이페이지' },
  { label: '설정' },
];
