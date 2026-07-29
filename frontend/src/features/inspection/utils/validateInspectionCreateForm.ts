import type { InspectionCreateRequest, InspectionType } from '../types';

// 폼 입력은 모두 문자열로 관리(빈 문자열 = 미입력) 후 제출 시 InspectionCreateRequest로 변환한다
// (validateFacilityForm.ts와 동일 패턴).
// assignedInspectorId는 더 이상 폼 입력이 아니다 — 담당 점검자 목록 조회 API가 없어 사용자 ID를
// 직접 입력받던 필드를 없애고, 제출 시 로그인한 사용자 본인을 담당자로 자동 배정한다(요청 반영).
export interface InspectionCreateFormValues {
  facilityId: string;
  /** YYYY-MM-DD */
  inspectionDate: string;
  inspectionType: InspectionType;
}

export type InspectionCreateFormErrors = Partial<Record<keyof InspectionCreateFormValues, string>>;

export const INSPECTION_CREATE_FORM_INITIAL_VALUES: InspectionCreateFormValues = {
  facilityId: '',
  inspectionDate: '',
  inspectionType: 'REGULAR',
};

// 로컬 타임존 기준 오늘 날짜(YYYY-MM-DD) — new Date(dateString) UTC 파싱과 비교하면 타임존
// 경계에서 하루가 밀릴 수 있어, 문자열끼리(둘 다 YYYY-MM-DD 고정폭이라 사전식 비교=날짜 비교) 비교한다.
// InspectionCreatePage가 <input type="date" max={...}>에도 그대로 재사용한다 — 네이티브 날짜
// 선택기에서부터 미래 날짜를 못 고르게 막아 제출 후에야 에러를 보여주는 것보다 먼저 막는다.
export function todayDateString(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

// 점검일은 실제로 점검을 수행한 날짜를 기록하는 필드다(회차 생성과 동시에 촬영 데이터를 업로드해
// AI 분석까지 이어지는 흐름) — 미래 날짜는 의미가 없어 거부한다. 백엔드
// InspectionService.validateInspectionDate와 동일 규칙 — API 왕복 없이 즉시 피드백 제공.
export function validateInspectionCreateForm(
  values: InspectionCreateFormValues,
): InspectionCreateFormErrors {
  const errors: InspectionCreateFormErrors = {};

  if (!values.facilityId) {
    errors.facilityId = '시설물을 선택해 주세요.';
  }

  if (!values.inspectionDate) {
    errors.inspectionDate = '점검일을 선택해 주세요.';
  } else if (values.inspectionDate > todayDateString()) {
    errors.inspectionDate = '점검일은 미래 날짜로 설정할 수 없습니다.';
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
    type: values.inspectionType,
  };
}
