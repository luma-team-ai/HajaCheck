import type { AdminUserPlan } from './types';
import type { PlanPolicyForm } from './planPolicy.types';

// "플랜 정책 설정" 모달 라벨·행 정의·초기값. Figma 시안(2026-07-23 첨부) 그대로.

export const PLAN_POLICY_COLUMN_ORDER: AdminUserPlan[] = ['FREE', 'STANDARD', 'ENTERPRISE'];

export const PLAN_POLICY_COLUMN_SUBLABEL: Record<AdminUserPlan, string> = {
  FREE: '무료 플랜',
  STANDARD: '표준 플랜',
  ENTERPRISE: '기업형 플랜',
};

interface PlanPolicyTextRowDef {
  no: string;
  key: 'priceMonthly' | 'maxFacilities' | 'maxMonthlyAnalyses' | 'maxSeats';
  label: string;
  /** 입력 우측에 붙는 단위(예: "KRW"). 없으면 미표시 */
  unit?: string;
  /**
   * 빈 값일 때 입력칸에 placeholder로 보여줄 안내 문구(예: "비워두면 무제한"). plans 테이블의 해당
   * 컬럼이 nullable이고 null = 무제한/협의라서, "무제한"·"협의"를 문자열로 직접 타이핑하게 하지 않고
   * 빈 값 자체로 그 의미를 표현한다(값을 그대로 저장 API에 보낼 때 "" → null 변환). placeholder라
   * 값 유무와 무관하게 입력칸 높이는 항상 동일하게 유지된다.
   */
  emptyHint?: string;
}

interface PlanPolicyToggleRowDef {
  no: string;
  key: 'hasPdfWatermark' | 'hasCounselorAccess';
  label: string;
}

export const PLAN_POLICY_TEXT_ROWS: PlanPolicyTextRowDef[] = [
  { no: '01', key: 'priceMonthly', label: '월 구독 가격', unit: 'KRW' },
  { no: '02', key: 'maxFacilities', label: '최대 등록 시설 수', emptyHint: '비워두면 무제한' },
  { no: '03', key: 'maxMonthlyAnalyses', label: '최대 분석 가능 횟수', emptyHint: '비워두면 협의' },
  { no: '04', key: 'maxSeats', label: '최대 사용자 좌석 수', emptyHint: '비워두면 무제한' },
];

export const PLAN_POLICY_TOGGLE_ROWS: PlanPolicyToggleRowDef[] = [
  { no: '05', key: 'hasPdfWatermark', label: '워터마크 표시 여부' },
  { no: '06', key: 'hasCounselorAccess', label: '전문 상담사 연결 제공 여부' },
];

// plans 테이블 시드값과 동일한 기준(#508 PRD 요금제안)의 초기 노출값 — 저장 API가 붙기 전까지
// 모달을 열 때마다 이 값에서 다시 시작한다.
export const PLAN_POLICY_DEFAULTS: PlanPolicyForm = {
  FREE: {
    priceMonthly: '0',
    maxFacilities: '1',
    maxMonthlyAnalyses: '50',
    maxSeats: '2',
    hasPdfWatermark: true,
    hasCounselorAccess: false,
  },
  STANDARD: {
    priceMonthly: '49000',
    maxFacilities: '10',
    maxMonthlyAnalyses: '1000',
    maxSeats: '20',
    hasPdfWatermark: false,
    hasCounselorAccess: true,
  },
  ENTERPRISE: {
    priceMonthly: '199000',
    // 무제한/협의는 문자열이 아니라 빈 값으로 표현한다(plans 테이블 nullable 컬럼과 대응)
    maxFacilities: '',
    maxMonthlyAnalyses: '',
    maxSeats: '',
    hasPdfWatermark: false,
    hasCounselorAccess: true,
  },
};
