import type { CreateFacilityRequest } from '../types';

// 폼 입력은 모두 문자열로 관리(빈 문자열 = 미입력) 후 제출 시 CreateFacilityRequest로 변환한다.
export interface FacilityFormValues {
  name: string;
  type: string;
  address: string;
  latitude: string;
  longitude: string;
  builtYear: string;
  scale: string;
  inspectionCycleMonths: string;
}

export type FacilityFormErrors = Partial<Record<keyof FacilityFormValues, string>>;

export const FACILITY_FORM_INITIAL_VALUES: FacilityFormValues = {
  name: '',
  type: '',
  address: '',
  latitude: '',
  longitude: '',
  builtYear: '',
  scale: '',
  inspectionCycleMonths: '',
};

const MAX_NAME_LENGTH = 200;
const MAX_TYPE_LENGTH = 20;
const MAX_ADDRESS_LENGTH = 300;
const MAX_SCALE_LENGTH = 100;
const MIN_LATITUDE = -90;
const MAX_LATITUDE = 90;
const MIN_LONGITUDE = -180;
const MAX_LONGITUDE = 180;

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

  if (values.latitude.trim()) {
    const latitude = Number(values.latitude);
    if (Number.isNaN(latitude) || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) {
      errors.latitude = `위도는 ${MIN_LATITUDE} ~ ${MAX_LATITUDE} 사이 숫자여야 합니다.`;
    }
  }

  if (values.longitude.trim()) {
    const longitude = Number(values.longitude);
    if (Number.isNaN(longitude) || longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE) {
      errors.longitude = `경도는 ${MIN_LONGITUDE} ~ ${MAX_LONGITUDE} 사이 숫자여야 합니다.`;
    }
  }

  if (values.builtYear.trim()) {
    const builtYear = Number(values.builtYear);
    if (!Number.isInteger(builtYear)) {
      errors.builtYear = '준공년도는 정수로 입력해 주세요.';
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
export function toCreateFacilityRequest(values: FacilityFormValues): CreateFacilityRequest {
  return {
    name: values.name.trim(),
    type: values.type.trim(),
    address: values.address.trim() || null,
    latitude: values.latitude.trim() ? Number(values.latitude) : null,
    longitude: values.longitude.trim() ? Number(values.longitude) : null,
    builtYear: values.builtYear.trim() ? Number(values.builtYear) : null,
    scale: values.scale.trim() || null,
    inspectionCycleMonths: values.inspectionCycleMonths.trim()
      ? Number(values.inspectionCycleMonths)
      : null,
  };
}
