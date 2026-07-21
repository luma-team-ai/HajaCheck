import type { InspectionCreateRequest } from '../types';

// 폼 입력은 모두 문자열로 관리(빈 문자열 = 미입력) 후 제출 시 InspectionCreateRequest로 변환한다
// (validateFacilityForm.ts와 동일 패턴).
export interface InspectionCreateFormValues {
  facilityId: string;
  /** YYYY-MM-DD */
  inspectionDate: string;
  assignedInspectorId: string;
}

export type InspectionCreateFormErrors = Partial<Record<keyof InspectionCreateFormValues, string>>;

export const INSPECTION_CREATE_FORM_INITIAL_VALUES: InspectionCreateFormValues = {
  facilityId: '',
  inspectionDate: '',
  assignedInspectorId: '',
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

  if (!values.assignedInspectorId.trim()) {
    errors.assignedInspectorId = '담당자 ID를 입력해 주세요.';
  } else {
    const id = Number(values.assignedInspectorId);
    if (!Number.isInteger(id) || id <= 0) {
      errors.assignedInspectorId = '담당자 ID는 1 이상의 정수여야 합니다.';
    }
  }

  return errors;
}

export function hasInspectionCreateFormErrors(errors: InspectionCreateFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

export function toInspectionCreateRequest(
  values: InspectionCreateFormValues,
): InspectionCreateRequest {
  return {
    facilityId: Number(values.facilityId),
    inspectionDate: values.inspectionDate,
    assignedInspectorId: Number(values.assignedInspectorId),
  };
}
