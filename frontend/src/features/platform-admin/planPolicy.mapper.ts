import { PLAN_POLICY_COLUMN_ORDER } from './planPolicy.constants';
import type { PlanPolicyForm, PlanPolicyValues } from './planPolicy.types';
import type { AdminUserPlan } from './types';

// GET /api/platform-admin/plans 응답(숫자·null) ↔ 모달 폼 상태(문자열, 빈 문자열=무제한/협의) 변환.
// plans 테이블 nullable 컬럼과 폼의 emptyHint 표현 규칙(planPolicy.constants.ts 참고)을 이어준다.

export interface PlanPolicyApiItem {
  name: AdminUserPlan;
  priceMonthly: number | null;
  maxFacilities: number | null;
  maxMonthlyAnalyses: number | null;
  maxSeats: number | null;
  hasPdfWatermark: boolean;
  hasCounselorAccess: boolean;
}

function numberToInputValue(value: number | null): string {
  return value === null ? '' : String(value);
}

function inputValueToNumber(value: string): number | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : Number(trimmed);
}

export function toPlanPolicyForm(items: PlanPolicyApiItem[]): PlanPolicyForm {
  const byName = new Map(items.map((item) => [item.name, item]));
  const form = {} as PlanPolicyForm;
  for (const plan of PLAN_POLICY_COLUMN_ORDER) {
    const item = byName.get(plan);
    form[plan] = {
      priceMonthly: item ? numberToInputValue(item.priceMonthly) : '0',
      maxFacilities: item ? numberToInputValue(item.maxFacilities) : '',
      maxMonthlyAnalyses: item ? numberToInputValue(item.maxMonthlyAnalyses) : '',
      maxSeats: item ? numberToInputValue(item.maxSeats) : '',
      hasPdfWatermark: item?.hasPdfWatermark ?? false,
      hasCounselorAccess: item?.hasCounselorAccess ?? false,
    };
  }
  return form;
}

/** priceMonthly는 필수 입력(빈 값 없음)이라 항상 숫자 — 폼의 emptyHint 대상이 아니다(가격 0은 무료를 의미). */
function toPriceNumber(values: PlanPolicyValues): number {
  return inputValueToNumber(values.priceMonthly) ?? 0;
}

export function fromPlanPolicyForm(form: PlanPolicyForm): PlanPolicyApiItem[] {
  return PLAN_POLICY_COLUMN_ORDER.map((plan) => {
    const values = form[plan];
    return {
      name: plan,
      priceMonthly: toPriceNumber(values),
      maxFacilities: inputValueToNumber(values.maxFacilities),
      maxMonthlyAnalyses: inputValueToNumber(values.maxMonthlyAnalyses),
      maxSeats: inputValueToNumber(values.maxSeats),
      hasPdfWatermark: values.hasPdfWatermark,
      hasCounselorAccess: values.hasCounselorAccess,
    };
  });
}
