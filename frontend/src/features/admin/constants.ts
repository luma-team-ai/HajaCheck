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

// 역할 배지 — 관리자만 검정 채움, 나머지는 연회색 pill(Figma)
export const ROLE_BADGE_CLASS: Record<AdminUserRole, string> = {
  USER: 'bg-neutral-100 text-text-default',
  ADMIN: 'bg-primary text-surface',
  INSPECTOR: 'bg-neutral-100 text-text-default',
  COUNSELOR: 'bg-neutral-100 text-text-default',
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
export const ROLE_FILTER_OPTIONS = Object.keys(ROLE_LABEL) as AdminUserRole[];
export const PLAN_FILTER_OPTIONS = Object.keys(PLAN_LABEL) as AdminUserPlan[];
export const STATUS_FILTER_OPTIONS = Object.keys(STATUS_LABEL) as AdminUserStatus[];

export const DEFAULT_PAGE_SIZE = 10;
