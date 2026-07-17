import type { PlanStatus, SeatMemberRole, SeatMemberStatus } from './types';

// success(초록)/warning(앰버) 색상 토큰이 shared/styles/tokens.css에 없다(2026-07-16 확인).
// tokens.css는 다른 오너의 자산이라 이번 스타일 전환 범위에서 건드리지 않고, 삭제된 mypage.css에
// 이미 있던 값을 그대로 이 로컬 상수로 이식한다(신규 색 도입 금지). --color-success/--color-warning
// 토큰 승격은 후속 이슈 — #293(auth.css)에서도 같은 사유로 hex가 P3로 잔존한 것과 동일한 트레이드오프.
//
// 주의: Tailwind는 클래스 문자열을 소스에서 정적으로 스캔하므로, 아래 클래스명은 전부 리터럴로
// 적는다(템플릿 리터럴로 조합하면 컴파일 시 인식되지 않아 스타일이 누락됨).

// 플랜 상태 배지 — Figma는 ACTIVE(보더형 pill)만 보여주지만, EXPIRED/UPGRADE_REQUESTED도 같은
// 보더형 스타일로 표현한다. 빨강(EXPIRED)은 신규 hex 대신 기존 tokens.css의 --color-danger 토큰을
// 그대로 쓴다(handoff 지시대로 — 새 빨강 hex 도입 금지).
export const PLAN_STATUS_BADGE_CLASS: Record<PlanStatus, string> = {
  ACTIVE: 'border-[#16a34a] text-[#16a34a]',
  EXPIRED: 'border-danger text-danger',
  UPGRADE_REQUESTED: 'border-[#b5670a] text-[#b5670a]',
};

// 좌석 역할 배지 — "소유자=검정 채움, 그 외=연회색"(Figma). 백엔드 Role에 owner 개념이 없어
// ADMIN을 소유자에 대응시킨다(#294 구현 서브 판단 — 보고에 명시).
export const SEAT_ROLE_BADGE_CLASS: Record<SeatMemberRole, string> = {
  ADMIN: 'bg-primary text-surface',
  INSPECTOR: 'bg-neutral-100 text-text-default',
  USER: 'bg-neutral-100 text-text-default',
  COUNSELOR: 'bg-neutral-100 text-text-default',
};

// 좌석 상태 색점 — ACTIVE는 success 색, SUSPENDED는 기존 danger 토큰 재사용
export const SEAT_STATUS_DOT_CLASS: Record<SeatMemberStatus, string> = {
  ACTIVE: 'bg-[#16a34a]',
  SUSPENDED: 'bg-danger',
};

// 사용량 임계 도달("N% 도달") 앰버 pill 배지
export const USAGE_WARNING_BADGE_CLASS = 'bg-[#fdf0d5] text-[#b5670a]';

// 사용량 프로그레스 바 채움색 — 평시는 tokens.css bg-primary(시안이 zinc-900 계열 바),
// 경고(80%↑)는 신규 hex 대신 기존 --color-danger 토큰 재사용
export const USAGE_BAR_FILL_CLASS = {
  normal: 'bg-primary',
  warning: 'bg-danger',
} as const;
