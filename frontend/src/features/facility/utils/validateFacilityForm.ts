import type { CreateFacilityRequest } from '../types';
import { computeNextInspectionDueAt } from './computeNextInspectionDueAt';

// 폼 입력은 모두 문자열로 관리(빈 문자열 = 미입력) 후 제출 시 CreateFacilityRequest로 변환한다.
// 위도/경도는 더 이상 수동 입력 필드가 아니다 — 주소 기반 Geocoder 자동 변환으로 대체됐다(#618).
// 계산된 좌표는 FacilityFormModal이 제출 시점에 별도로 toCreateFacilityRequest 결과에 병합한다.
export interface FacilityFormValues {
  name: string;
  type: string;
  address: string;
  builtYear: string;
  scale: string;
  inspectionCycleMonths: string;
}

export type FacilityFormErrors = Partial<Record<keyof FacilityFormValues, string>>;

export const FACILITY_FORM_INITIAL_VALUES: FacilityFormValues = {
  name: '',
  type: '',
  address: '',
  builtYear: '',
  scale: '',
  inspectionCycleMonths: '',
};

const MAX_NAME_LENGTH = 200;
const MAX_TYPE_LENGTH = 20;
const MAX_ADDRESS_LENGTH = 300;
const MAX_SCALE_LENGTH = 100;
const MIN_BUILT_YEAR = 1900;

// 준공년도 상한 — 준공이 미래일 수는 없으므로 현재연도가 기준이나, "내년 준공 예정" 등록을 허용해 +1 까지 받는다.
// 해가 바뀌어도 따라가도록 호출 시점에 산출한다(연도 하드코딩 금지).
function maxBuiltYear(): number {
  return new Date().getFullYear() + 1;
}

// 백엔드 계약(name/type 필수, 각 필드 길이·범위 제약)과 1:1 — API 왕복 없이 즉시 피드백 제공
export function validateFacilityForm(values: FacilityFormValues): FacilityFormErrors {
  const errors: FacilityFormErrors = {};

  if (!values.name.trim()) {
    errors.name = '시설물명을 입력해 주세요.';
  } else if (values.name.length > MAX_NAME_LENGTH) {
    errors.name = `시설물명은 ${MAX_NAME_LENGTH}자 이하로 입력해 주세요.`;
  }

  if (!values.type.trim()) {
    errors.type = '시설물 유형을 선택해 주세요.';
  } else if (values.type.length > MAX_TYPE_LENGTH) {
    errors.type = `시설물 유형은 ${MAX_TYPE_LENGTH}자 이하로 입력해 주세요.`;
  }

  if (values.address.length > MAX_ADDRESS_LENGTH) {
    errors.address = `주소는 ${MAX_ADDRESS_LENGTH}자 이하로 입력해 주세요.`;
  }

  if (values.builtYear.trim()) {
    const builtYear = Number(values.builtYear);
    const maxYear = maxBuiltYear();
    if (!Number.isInteger(builtYear)) {
      errors.builtYear = '준공년도는 정수로 입력해 주세요.';
    } else if (builtYear < MIN_BUILT_YEAR || builtYear > maxYear) {
      // 정수 검사만 있으면 -100·999999 같은 값이 그대로 서버로 전송된다(#224 P2 → #352).
      errors.builtYear = `준공년도는 ${MIN_BUILT_YEAR} ~ ${maxYear} 사이로 입력해 주세요.`;
    }
  }

  if (values.scale.length > MAX_SCALE_LENGTH) {
    errors.scale = `규모는 ${MAX_SCALE_LENGTH}자 이하로 입력해 주세요.`;
  }

  if (values.inspectionCycleMonths.trim()) {
    const months = Number(values.inspectionCycleMonths);
    if (!Number.isInteger(months) || months < 0) {
      errors.inspectionCycleMonths = '점검 주기는 0 이상의 정수(개월)로 입력해 주세요.';
    }
  }

  return errors;
}

export function hasFacilityFormErrors(errors: FacilityFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

// 빈 문자열 입력은 옵셔널 필드를 null로 취급 — 서버에 불필요한 빈 문자열을 보내지 않는다.
// nextInspectionDueAt은 백엔드가 자동계산하지 않으므로(FacilityService는 패스스루 저장만) 여기서
// inspectionCycleMonths 기준으로 산정해 함께 전송한다 — computeNextInspectionDueAt은 MSW 목과 공용.
// latitude/longitude는 여기서 채우지 않는다 — 주소 기반 Geocoder 자동 변환 결과를 FacilityFormModal이
// 제출 시점에 이 함수의 반환값 위에 병합한다(#618, 수동 입력 필드 제거).
export function toCreateFacilityRequest(values: FacilityFormValues): CreateFacilityRequest {
  const inspectionCycleMonths = values.inspectionCycleMonths.trim()
    ? Number(values.inspectionCycleMonths)
    : null;

  return {
    name: values.name.trim(),
    type: values.type.trim(),
    address: values.address.trim() || null,
    latitude: null,
    longitude: null,
    builtYear: values.builtYear.trim() ? Number(values.builtYear) : null,
    scale: values.scale.trim() || null,
    inspectionCycleMonths,
    nextInspectionDueAt: computeNextInspectionDueAt(inspectionCycleMonths),
  };
}
