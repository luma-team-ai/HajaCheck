import { findFacilityTypeCycleMonths } from '../constants';
import type { CreateFacilityRequest, FacilityInitialGrade } from '../types';
import { computeNextInspectionDueAt } from './computeNextInspectionDueAt';

// 폼 입력은 모두 문자열로 관리(빈 문자열 = 미입력) 후 제출 시 CreateFacilityRequest로 변환한다.
// 위도/경도는 더 이상 수동 입력 필드가 아니다 — 주소 기반 Geocoder 자동 변환으로 대체됐다(#618).
// 계산된 좌표는 FacilityFormModal이 제출 시점에 별도로 toCreateFacilityRequest 결과에 병합한다.
// 점검주기(inspectionCycleMonths)·규모(scale)는 등록 폼에서 제거했다(#629 — 둘 다 optional이라
// 계약 문제 없음, 점검주기는 별도 "점검 주기 설정" 화면(dev-04-03)에서 이미 처리한다).
// address(도로명)/addressDetail(상세)은 FacilityAddressField가 각각 관리하다가 제출 시 하나의
// 문자열로 합쳐 CreateFacilityRequest.address(백엔드 단일 컬럼, ≤300자)로 전송한다.
export interface FacilityFormValues {
  name: string;
  type: string;
  address: string;
  addressDetail: string;
  builtYear: string;
  // #628(HAJA-347) 등록 필드 확장 — 전부 선택 입력
  initialGrade: FacilityInitialGrade | '';
  assigneeUserId: string;
  memo: string;
}

export type FacilityFormErrors = Partial<Record<keyof FacilityFormValues, string>>;

export const FACILITY_FORM_INITIAL_VALUES: FacilityFormValues = {
  name: '',
  type: '',
  address: '',
  addressDetail: '',
  builtYear: '',
  initialGrade: '',
  assigneeUserId: '',
  memo: '',
};

const MAX_NAME_LENGTH = 200;
const MAX_TYPE_LENGTH = 20;
const MAX_ADDRESS_LENGTH = 300;
const MAX_MEMO_LENGTH = 2000;
const MIN_BUILT_YEAR = 1900;

// 준공년도 상한 — 준공이 미래일 수는 없으므로 현재연도가 기준이나, "내년 준공 예정" 등록을 허용해 +1 까지 받는다.
// 해가 바뀌어도 따라가도록 호출 시점에 산출한다(연도 하드코딩 금지).
function maxBuiltYear(): number {
  return new Date().getFullYear() + 1;
}

// 도로명주소(주소검색 결과)와 상세주소를 하나의 문자열로 합친다 — 백엔드 Facility.address는
// 단일 컬럼(≤300자)이라 CompanyAddressField와 달리 별도 컬럼으로 분리 전송할 수 없다(#629).
function joinAddress(address: string, addressDetail: string): string {
  return [address.trim(), addressDetail.trim()].filter(Boolean).join(' ');
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

  if (joinAddress(values.address, values.addressDetail).length > MAX_ADDRESS_LENGTH) {
    errors.address = `주소(상세주소 포함)는 ${MAX_ADDRESS_LENGTH}자 이하로 입력해 주세요.`;
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

  if (values.assigneeUserId.trim()) {
    const assigneeUserId = Number(values.assigneeUserId);
    if (!Number.isInteger(assigneeUserId) || assigneeUserId <= 0) {
      errors.assigneeUserId = '담당자를 다시 선택해 주세요.';
    }
  }

  if (values.memo.length > MAX_MEMO_LENGTH) {
    errors.memo = `메모는 ${MAX_MEMO_LENGTH}자 이하로 입력해 주세요.`;
  }

  return errors;
}

export function hasFacilityFormErrors(errors: FacilityFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

// 빈 문자열 입력은 옵셔널 필드를 null로 취급 — 서버에 불필요한 빈 문자열을 보내지 않는다.
// 점검주기(inspectionCycleMonths)는 별도 입력 필드가 아니라 선택된 유형(values.type)에서
// 파생한다(#731) — 12개 조합 옵션(constants.ts FACILITY_TYPE_OPTIONS) 중 정확히 일치하는
// value가 있으면 그 cycleMonths를, 없으면(과거 데이터·예외적 자유 입력) null을 사용한다.
// nextInspectionDueAt은 그 cycleMonths 기준으로 computeNextInspectionDueAt이 자동 계산한다 —
// 별도 "점검 주기 설정" 화면(dev-04-03, POST .../schedule)에서 이후 재조정도 가능하다.
// latitude/longitude는 여기서 채우지 않는다 — 주소 기반 Geocoder 자동 변환 결과를 FacilityFormModal이
// 제출 시점에 이 함수의 반환값 위에 병합한다(#618, 수동 입력 필드 제거).
export function toCreateFacilityRequest(values: FacilityFormValues): CreateFacilityRequest {
  const address = joinAddress(values.address, values.addressDetail) || null;
  const inspectionCycleMonths = findFacilityTypeCycleMonths(values.type);

  return {
    name: values.name.trim(),
    type: values.type.trim(),
    address,
    latitude: null,
    longitude: null,
    builtYear: values.builtYear.trim() ? Number(values.builtYear) : null,
    scale: null,
    inspectionCycleMonths,
    nextInspectionDueAt: computeNextInspectionDueAt(inspectionCycleMonths),
    initialGrade: values.initialGrade || null,
    assigneeUserId: values.assigneeUserId.trim() ? Number(values.assigneeUserId) : null,
    memo: values.memo.trim() || null,
  };
}
