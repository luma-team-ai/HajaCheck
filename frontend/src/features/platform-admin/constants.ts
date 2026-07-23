import type { SideNavItem } from '../../shared/components/SideNavBar';
import adminIcon from '../../assets/brand/sidenav-admin.svg';
import type { AdminUserPlan, AdminUserRole, AdminUserStatus, CompanyOption } from './types';

// 플랫폼 관리자 콘솔 사이드바(#535) — 7개 메뉴 라벨은 기존 SideNavBar DEFAULT_ADMIN_ITEM(기업
// 관리자 콘솔)의 subItems 라벨과 동일하게 유지하고, 경로만 /platform-admin/* 로 분리한다.
// PlatformAdminShellRoute가 SideNavBar에 items=[], adminItem=이 값을 넘기면 이 그룹 하나만
// 노출된다(SideNavBar는 isAdmin=true일 때 [...items, adminItem]을 렌더).
export const PLATFORM_ADMIN_NAV_ITEM: SideNavItem = {
  label: '플랫폼 관리자',
  href: '/platform-admin',
  icon: adminIcon,
  subItems: [
    { label: '사용자 관리', href: '/platform-admin/users' },
    { label: '플랜·쿼터 관리', href: '/platform-admin/plans-quota' },
    { label: '하자 유형·등급 관리', href: '/platform-admin/defect-types' },
    { label: '상담 관리', href: '/platform-admin/counsels' },
    { label: 'RAG 문서 관리', href: '/platform-admin/rag-documents' },
    { label: '서비스 통계', href: '/platform-admin/stats' },
    { label: '시스템 모니터링', href: '/platform-admin/monitoring' },
  ],
};

// 플랫폼 관리자 > 사용자 관리(#577) — features/admin/constants.ts(#405 기업 관리자 사용자 관리)를
// 그대로 옮긴 것. 라벨·배지 스타일은 기업 관리자 화면과 동일하게 유지한다.

export const ROLE_LABEL: Record<AdminUserRole, string> = {
  USER: '일반',
  ADMIN: '관리자',
  INSPECTOR: '점검자',
  COUNSELOR: '상담원',
};

export const PLAN_LABEL: Record<AdminUserPlan, string> = {
  FREE: 'Free',
  STANDARD: 'Standard',
  ENTERPRISE: 'Enterprise',
};

export const STATUS_LABEL: Record<AdminUserStatus, string> = {
  ACTIVE: '활성',
  SUSPENDED: '정지',
};

export const EMPTY_CELL = '-';

export const ROLE_BADGE_CLASS: Record<AdminUserRole, string> = {
  USER: 'bg-neutral-100 text-text-default',
  ADMIN: 'bg-primary text-surface',
  INSPECTOR: 'bg-warning-soft-bg text-warning-soft-fg',
  COUNSELOR: 'bg-info-soft-bg text-info-soft-fg',
};

export const PLAN_BADGE_CLASS: Record<AdminUserPlan, string> = {
  FREE: 'border-border text-text-default',
  STANDARD: 'border-border text-text-default',
  ENTERPRISE: 'border-primary font-semibold text-primary',
};

export const STATUS_DOT_CLASS: Record<AdminUserStatus, string> = {
  ACTIVE: 'bg-[#16a34a]',
  SUSPENDED: 'bg-danger',
};

export const GROWTH_UP_CLASS = 'text-[#16a34a]';

export const ROLE_FILTER_OPTIONS = (Object.keys(ROLE_LABEL) as AdminUserRole[]).filter(
  (role) => role !== 'COUNSELOR',
);
// features/admin와 달리 여기선 플랜 필터를 유지한다 — 전사 조회라 회사별 구독 플랜으로 좁혀볼 일이
// 실제로 있다(사용자 지시, #577 후속).
export const PLAN_FILTER_OPTIONS = Object.keys(PLAN_LABEL) as AdminUserPlan[];
export const STATUS_FILTER_OPTIONS = Object.keys(STATUS_LABEL) as AdminUserStatus[];

// 사용자 등록 모달의 기업명 selectbox — 실 백엔드 GET /api/platform-admin/companies가 준비되기
// 전까지의 선제 정의(planQuota.types.ts와 동일한 전략). "선택 안함" 선택 시 companyId=null로
// 전송되어 개인(회사 미소속) 계정으로 등록된다.
export const COMPANY_OPTIONS: CompanyOption[] = [
  { id: 1, name: '테크노빌딩관리' },
  { id: 2, name: '그린타워시설관리' },
  { id: 3, name: '한빛건설' },
  { id: 4, name: '스마트파크FM' },
  { id: 5, name: '유니온시설서비스' },
];

export const DEFAULT_PAGE_SIZE = 10;

export const ROLE_CHANGE_OPTIONS: { role: 'USER' | 'INSPECTOR' | 'ADMIN'; description: string }[] = [
  { role: 'USER', description: '시설물 조회 및 개인 설정 관리 권한' },
  { role: 'INSPECTOR', description: '현장 데이터 업로드 및 기본 조회 권한' },
  { role: 'ADMIN', description: '소속 기업의 데이터 및 사용자 관리 권한' },
];

export const STATUS_CHANGE_OPTIONS: { status: AdminUserStatus; description: string }[] = [
  { status: 'ACTIVE', description: '시스템 모든 기능을 정상적으로 이용할 수 있습니다.' },
  { status: 'SUSPENDED', description: '시스템 로그인이 차단되며 모든 서비스 이용이 제한됩니다.' },
];
