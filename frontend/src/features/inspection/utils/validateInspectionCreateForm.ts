import type { InspectionCreateRequest } from '../types';

// 폼 입력은 모두 문자열로 관리(빈 문자열 = 미입력) 후 제출 시 InspectionCreateRequest로 변환한다
// (validateFacilityForm.ts와 동일 패턴).
// assignedInspectorId는 더 이상 폼 입력이 아니다 — 담당 점검자 목록 조회 API가 없어 사용자 ID를
// 직접 입력받던 필드를 없애고, 제출 시 로그인한 사용자 본인을 담당자로 자동 배정한다(요청 반영).
export interface InspectionCreateFormValues {
  facilityId: string;
  /** YYYY-MM-DD */
  inspectionDate: string;
}

export type InspectionCreateFormErrors = Partial<Record<keyof InspectionCreateFormValues, string>>;

export const INSPECTION_CREATE_FORM_INITIAL_VALUES: InspectionCreateFormValues = {
  facilityId: '',
  inspectionDate: '',
};

// 백엔드 InspectionService.MAX_FUTURE_MONTHS(12개월)와 동일 규칙 — API 왕복 없이 즉시 피드백 제공.
const MAX_FUTURE_MONTHS = 12;

export function validateInspectionCreateForm(
  values: InspectionCreateFormValues,
): InspectionCreateFormErrors {
  const errors: InspectionCreateFormErrors = {};

  if (!values.facilityId) {
    errors.facilityId = '시설물을 선택해 주세요.';
  }

  if (!values.inspectionDate) {
    errors.inspectionDate = '점검일을 선택해 주세요.';
  } else {
    const maxDate = new Date();
    maxDate.setMonth(maxDate.getMonth() + MAX_FUTURE_MONTHS);
    if (new Date(values.inspectionDate) > maxDate) {
      errors.inspectionDate = `점검일은 오늘로부터 ${MAX_FUTURE_MONTHS}개월 이내여야 합니다.`;
    }
  }

  return errors;
}

export function hasInspectionCreateFormErrors(errors: InspectionCreateFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

// assignedInspectorId는 로그인한 사용자 본인 id를 호출부(InspectionCreatePage)가 넘긴다 —
// 담당자 배정 검증(AuthService.validateAssignableInspector)은 여전히 백엔드가 수행한다
// (본인이 회사 소속 INSPECTOR/ADMIN이 아니면 그대로 거부된다).
export function toInspectionCreateRequest(
  values: InspectionCreateFormValues,
  assignedInspectorId: number,
): InspectionCreateRequest {
  return {
    facilityId: Number(values.facilityId),
    inspectionDate: values.inspectionDate,
    assignedInspectorId,
  };
}
