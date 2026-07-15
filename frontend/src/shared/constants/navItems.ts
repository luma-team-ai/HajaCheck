// 전역 사이드바 네비게이션 구성 — dev-03-01(Figma) 기준
// 여러 feature(dashboard·mypage 등)가 공유하는 앱 셸 데이터라 shared로 승격
// (React_코드_컨벤션.md §1 "공유가 필요해지면 shared로 승격"; HAJA-185 마이페이지 활성화 시 이관)
// 실제 구현된 경로: /dashboard, /mypage/plan — 나머지는 담당 feature 착수 전까지 비활성 처리

export interface DashboardNavSubItem {
  label: string;
  path: string;
  isActive: boolean;
}

// Figma dev-03-01 기준 메뉴별 아이콘 — shared/components/layout/NavIcon.tsx가 이 이름으로 SVG를 렌더링
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
  {
    label: '마이페이지',
    icon: 'mypage',
    subItems: [
      { label: '내 정보', path: '#', isActive: false },
      { label: '내 점검 이력·보고서', path: '#', isActive: false },
      { label: '내 플랜', path: '/mypage/plan', isActive: true },
    ],
  },
  { label: '설정', icon: 'settings' },
];
