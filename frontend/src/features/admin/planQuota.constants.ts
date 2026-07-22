import { PLAN_LABEL } from './constants';
import type { AdminPlanCatalogItem, PlanDetail } from './planQuota.types';

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

// 플랜 부제·CTA 라벨 — PRD_hajaCheck.md §2.4 "요금제(안)" 표의 "대상" 컬럼을 그대로 쓴다
// (Free="체험·개인", Standard="소규모 점검 업체", Enterprise="관리업체·공공"). 가격·기능 한도는
// 여기 하드코딩하지 않고 plans 테이블(GET /api/admin/plans → AdminPlanCatalogItem)에서 가져온다.
const PLAN_TAGLINE: Record<AdminPlanCatalogItem['name'], string> = {
  FREE: '체험·개인 사용자',
  STANDARD: '소규모 점검 업체',
  ENTERPRISE: '관리업체·공공',
};

const PLAN_CTA_LABEL: Record<AdminPlanCatalogItem['name'], string> = {
  FREE: '무료 시작',
  STANDARD: '플랜 관리',
  ENTERPRISE: '플랜 관리',
};

// max_seats DDL 컬럼은 NOT NULL이라 "무제한"을 null로 표현할 수 없다 — 이 값 이상이면 무제한으로 표시한다
// (실제 시드값 1,000,000 — docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql 참고).
const UNLIMITED_SEAT_THRESHOLD = 9999;
// FREE 시드값은 0이 아니라 1(계정 소유자 본인 1석) — "점검자 초대 좌석 없음"은 <=1로 판정한다
// (같은 마이그레이션: ('FREE', ..., max_seats=1, ...)).
const NO_ADDITIONAL_SEAT_THRESHOLD = 1;

function formatFacilityAndAnalysisLimit(item: AdminPlanCatalogItem): string {
  const facility = item.maxFacilities === null ? '무제한' : `${item.maxFacilities}개`;
  const analysis =
    item.maxMonthlyAnalyses === null ? '무제한(협의)' : `${item.maxMonthlyAnalyses.toLocaleString('ko-KR')}장`;
  return `시설물 ${facility} · 월 분석 ${analysis}`;
}

function hasAdditionalSeats(item: AdminPlanCatalogItem): boolean {
  return item.maxSeats > NO_ADDITIONAL_SEAT_THRESHOLD;
}

function formatSeatLimit(item: AdminPlanCatalogItem): string {
  if (!hasAdditionalSeats(item)) {
    return '점검자 좌석 없음(1인 계정)';
  }
  if (item.maxSeats >= UNLIMITED_SEAT_THRESHOLD) {
    return '점검자 좌석 무제한';
  }
  return `점검자 좌석 ${item.maxSeats}명`;
}

/** plans 테이블 응답(AdminPlanCatalogItem) → "현재 플랜" 카드 표시용 상세로 변환한다. */
export function buildPlanDetail(item: AdminPlanCatalogItem): PlanDetail {
  return {
    name: item.name,
    tagline: PLAN_TAGLINE[item.name],
    priceMonthly: item.priceMonthly,
    features: [
      { label: formatFacilityAndAnalysisLimit(item), included: true },
      { label: formatSeatLimit(item), included: hasAdditionalSeats(item) },
      { label: item.hasPdfWatermark ? '보고서(워터마크)' : '정식 PDF 보고서', included: true },
      { label: item.hasCounselorAccess ? '상담원 연결' : '시나리오 챗봇만', included: true },
      { label: 'AI 부가 기능(설명·브리핑)', included: item.hasAiAddon },
    ],
    ctaLabel: PLAN_CTA_LABEL[item.name],
  };
}
