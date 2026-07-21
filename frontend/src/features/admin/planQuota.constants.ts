import { PLAN_LABEL } from './constants';
import type { AdminUserPlan, PlanDetail } from './planQuota.types';

// 관리자 > 플랜·쿼터 관리 라벨·임계값·플랜 상세 — Figma node-id 1197-3519.
// 플랜 이름 라벨은 사용자 관리와 동일하므로 constants.ts의 PLAN_LABEL을 재사용한다.

export { PLAN_LABEL };

export const PLAN_QUOTA_DEFAULT_PAGE_SIZE = 4;

/** 최근 접속·플랜이 비어있을 때 셀 표시 */
export const PLAN_QUOTA_EMPTY_CELL = '-';

/** 무제한(quotaLimit=null) 한도 표시 문구 */
export const UNLIMITED_LABEL = '무제한';

// 개별 쿼터 사용률이 이 값 이상이면 경고 색(주황)으로 강조 — Figma에서 96%가 주황(84%는 검정)
export const QUOTA_WARNING_PERCENT = 90;

// 쿼터 바 색 — 정상은 primary(검정), 경고는 주황. Tailwind가 클래스 문자열을 정적으로 스캔하므로 리터럴로 적는다.
export const QUOTA_BAR_NORMAL_CLASS = 'bg-primary';
export const QUOTA_BAR_WARNING_CLASS = 'bg-[#f97316]';
export const QUOTA_TEXT_NORMAL_CLASS = 'text-text-default';
export const QUOTA_TEXT_WARNING_CLASS = 'text-[#f97316]';

// 플랜별 상세 — "현재 플랜" 카드가 선택된 행의 plan에 따라 이 정의를 렌더한다.
// 백엔드 플랜 마스터가 아직 없어 화면 표기용으로 선제 정의(가격/기능은 시안 기준 예시값).
export const PLAN_DETAILS: Record<AdminUserPlan, PlanDetail> = {
  FREE: {
    name: 'FREE',
    tagline: '체험·개인 사용자',
    priceMonthly: 0,
    features: [
      { label: '시설물 1개 · 월 분석 50장', included: true },
      { label: '점검 좌석 없음(1인 계정)', included: true },
      { label: 'AI 부가 기능(설명·브리핑)', included: false },
    ],
    ctaLabel: '무료 시작',
  },
  STANDARD: {
    name: 'STANDARD',
    tagline: '성장하는 팀',
    priceMonthly: 90000,
    features: [
      { label: '시설물 20개 · 월 분석 2,000장', included: true },
      { label: '점검 좌석 10석', included: true },
      { label: 'AI 부가 기능(설명·브리핑)', included: true },
    ],
    ctaLabel: '플랜 관리',
  },
  ENTERPRISE: {
    name: 'ENTERPRISE',
    tagline: '대규모 조직',
    priceMonthly: 350000,
    features: [
      { label: '시설물 무제한 · 월 분석 무제한', included: true },
      { label: '점검 좌석 무제한', included: true },
      { label: 'AI 부가 기능 + 전담 지원', included: true },
    ],
    ctaLabel: '플랜 관리',
  },
};
