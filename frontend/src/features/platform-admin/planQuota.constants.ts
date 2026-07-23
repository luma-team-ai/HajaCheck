import { PLAN_LABEL } from './constants';
import type { AdminUserPlan } from './types';
import type { PlanQuotaUserStatus } from './planQuota.types';

// 플랫폼 관리자 > 플랜·쿼터 관리 라벨·임계값. 플랜 이름 라벨은 사용자 관리와 동일하므로
// constants.ts의 PLAN_LABEL을 재사용한다.

export { PLAN_LABEL };

export const PLAN_QUOTA_DEFAULT_PAGE_SIZE = 4;

/** 최근 접속·플랜이 비어있을 때 셀 표시 */
export const PLAN_QUOTA_EMPTY_CELL = '-';

/** 무제한(quotaLimit=null) 한도 표시 문구 */
export const UNLIMITED_LABEL = '무제한';

// 개별 쿼터 사용률이 이 값 이상이면 경고 색(주황)으로 강조 — Figma에서 96%가 주황(84%는 검정)
export const QUOTA_WARNING_PERCENT = 90;

// 쿼터 바 색 — 기존 primary(거의 검정, #18181b)가 옅은 트랙 배경 위에서도 잘 안 보인다는 지적(사용자
// 지시)에 따라 선명한 파랑/주황으로 교체하고 바 두께도 키운다(QuotaUsageBar.tsx 참고).
// Tailwind가 클래스 문자열을 정적으로 스캔하므로 리터럴로 적는다.
export const QUOTA_BAR_NORMAL_CLASS = 'bg-[#2563eb]';
export const QUOTA_BAR_WARNING_CLASS = 'bg-[#f97316]';
export const QUOTA_TEXT_NORMAL_CLASS = 'text-[#2563eb]';
export const QUOTA_TEXT_WARNING_CLASS = 'text-[#f97316]';

// 플랜 배지 색 — Free/Standard/Enterprise를 한눈에 구분하도록(사용자 지시) 회색/파랑/보라 계열로 분리.
// 사용자 관리 화면의 PLAN_BADGE_CLASS(테두리 위주)와 달리 이 표는 배경색 채움 배지를 쓴다.
export const PLAN_QUOTA_BADGE_CLASS: Record<AdminUserPlan, string> = {
  FREE: 'bg-neutral-100 text-text-default',
  STANDARD: 'bg-[#eff6ff] text-[#2563eb]',
  ENTERPRISE: 'bg-[rgba(217,70,239,0.1)] text-[#a21caf] font-semibold',
};

/** "상태" 컬럼 배지 라벨 — Figma node-id 1206-2639 */
export const PLAN_QUOTA_STATUS_LABEL: Record<PlanQuotaUserStatus, string> = {
  ACTIVE: '활성',
  WARNING: '주의',
  EXPIRED: '만료',
};

// 상태 점·텍스트 색상 — 활성(보라)/주의(주황)/만료(빨강). Tailwind가 클래스 문자열을 정적으로
// 스캔하므로 리터럴로 적는다.
export const PLAN_QUOTA_STATUS_DOT_CLASS: Record<PlanQuotaUserStatus, string> = {
  ACTIVE: 'bg-[#6366f1]',
  WARNING: 'bg-[#f97316]',
  EXPIRED: 'bg-danger',
};

export const PLAN_QUOTA_STATUS_TEXT_CLASS: Record<PlanQuotaUserStatus, string> = {
  ACTIVE: 'text-text-default',
  WARNING: 'text-[#f97316]',
  EXPIRED: 'text-danger',
};

/** "남은 기간" 컬럼 — 만료(remainingDays=null)면 "만료됨"으로 강조 표시 */
export const PLAN_QUOTA_EXPIRED_LABEL = '만료됨';
