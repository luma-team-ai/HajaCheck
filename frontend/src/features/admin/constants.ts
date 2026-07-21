import type { AdminUserPlan, AdminUserRole, AdminUserStatus } from './types';

// 관리자 > 사용자 관리 라벨·배지 스타일 — Figma node-id 177-2017.
// success(초록) 토큰이 shared/styles/tokens.css에 없고 tokens.css는 다른 오너 자산이라 건드리지 않는다
// (features/mypage/statusClasses.ts와 동일한 트레이드오프 — 토큰 승격은 후속 이슈, P3).
//
// 주의: Tailwind는 클래스 문자열을 소스에서 정적으로 스캔하므로 아래 클래스명은 전부 리터럴로 적는다.

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

/** 최근 접속 이력이 없거나(미접속) 활성 구독이 없을 때 셀 표시 */
export const EMPTY_CELL = '-';

// 역할 배지 — Figma 원안은 관리자만 검정, 나머지 전부 동일한 연회색 pill이라 역할 구분이 한눈에
// 안 됐다(#405 리뷰 지적). USER(기본 역할)만 중립 회색으로 남기고 나머지 3종은 서로 다른 색을
// 줘 구분되게 한다. 새 hex를 만들지 않고 이미 tokens.css에 승격된 soft 색상 쌍을 재사용한다
// (React_코드_컨벤션.md §8 "컴포넌트에 hex 하드코딩 금지").
export const ROLE_BADGE_CLASS: Record<AdminUserRole, string> = {
  USER: 'bg-neutral-100 text-text-default',
  ADMIN: 'bg-primary text-surface',
  INSPECTOR: 'bg-warning-soft-bg text-warning-soft-fg',
  COUNSELOR: 'bg-info-soft-bg text-info-soft-fg',
};

// 플랜 배지 — 흰 배경 보더형. Enterprise만 진한 보더로 강조(Figma)
export const PLAN_BADGE_CLASS: Record<AdminUserPlan, string> = {
  FREE: 'border-border text-text-default',
  STANDARD: 'border-border text-text-default',
  ENTERPRISE: 'border-primary font-semibold text-primary',
};

// 상태 색점 — 활성은 mypage와 동일한 success hex 재사용, 정지는 기존 --color-danger 토큰
export const STATUS_DOT_CLASS: Record<AdminUserStatus, string> = {
  ACTIVE: 'bg-[#16a34a]',
  SUSPENDED: 'bg-danger',
};

/** 신규 가입 증감률 상승 표시 색 — 활성 색점과 동일 계열 */
export const GROWTH_UP_CLASS = 'text-[#16a34a]';

// 필터 드롭다운 옵션 — '전체'는 빈 문자열로 표현해 params에서 제외한다
// 역할 필터에서 상담원(COUNSELOR)은 제외한다(#405 리뷰 지적) — 배지 라벨(ROLE_LABEL)은
// 기존 상담원 사용자 표시를 위해 남겨두고, 필터링 대상에서만 뺀다.
export const ROLE_FILTER_OPTIONS = (Object.keys(ROLE_LABEL) as AdminUserRole[]).filter(
  (role) => role !== 'COUNSELOR',
);
export const STATUS_FILTER_OPTIONS = Object.keys(STATUS_LABEL) as AdminUserStatus[];

export const DEFAULT_PAGE_SIZE = 10;

// 역할 변경 모달 — Figma node-id 991-2926. 상담원(COUNSELOR)은 배정 대상이 아니라 제외하고,
// 표시 순서는 권한 범위가 좁은 순(일반 → 점검자 → 관리자)으로 Figma를 그대로 따른다.
export const ROLE_CHANGE_OPTIONS: { role: 'USER' | 'INSPECTOR' | 'ADMIN'; description: string }[] = [
  { role: 'USER', description: '시설물 조회 및 개인 설정 관리 권한' },
  { role: 'INSPECTOR', description: '현장 데이터 업로드 및 기본 조회 권한' },
  { role: 'ADMIN', description: '소속 기업의 데이터 및 사용자 관리 권한' },
];

// 상태 변경 모달 — Figma node-id 991-3102.
export const STATUS_CHANGE_OPTIONS: { status: AdminUserStatus; description: string }[] = [
  { status: 'ACTIVE', description: '시스템 모든 기능을 정상적으로 이용할 수 있습니다.' },
  { status: 'SUSPENDED', description: '시스템 로그인이 차단되며 모든 서비스 이용이 제한됩니다.' },
];
