import type { Facility, FacilityInitialGrade } from '../types';

// 시설물 목록 검색+필터(#810) — 백엔드 GET /api/facilities가 이미 name/type/address/initialGrade를
// 전부 내려주므로(FacilityResponse.java) 신규 API 없이, 전체 목록을 한 번에 불러온 뒤(기존 useFacilities
// 패턴 그대로) 이 순수 함수로 클라이언트 사이드 필터링한다.
export interface FacilityListFilters {
  search: string;
  type: string;
  region: string;
  grade: FacilityInitialGrade | '';
}

export const FACILITY_LIST_FILTERS_INITIAL: FacilityListFilters = {
  search: '',
  type: '',
  region: '',
  grade: '',
};

// address 첫 토큰(공백 기준)을 지역으로 취급하는 단순 파생 규칙(이슈 #810 명시 스펙) —
// "서울 강남구 테헤란로 123" → "서울". address가 없는 시설물은 지역을 파생할 수 없으므로
// null을 반환해 호출부(옵션 구성·필터 매칭 양쪽)가 에러 없이 안전하게 제외하도록 한다.
export function parseRegionFromAddress(address: string | null): string | null {
  if (!address) {
    return null;
  }
  const [region] = address.trim().split(/\s+/);
  return region || null;
}

// 유형 드롭다운 옵션 — 하드코딩 대신 현재 로드된 목록에서 distinct 값을 동적으로 구성한다(#810 요구사항).
export function getFacilityTypeOptions(facilities: Facility[]): string[] {
  const types = new Set(facilities.map((facility) => facility.type));
  return Array.from(types).sort((a, b) => a.localeCompare(b, 'ko'));
}

// 지역 드롭다운 옵션 — address가 null인 시설물은 parseRegionFromAddress가 null을 반환하므로 자동 제외된다.
export function getFacilityRegionOptions(facilities: Facility[]): string[] {
  const regions = facilities
    .map((facility) => parseRegionFromAddress(facility.address))
    .filter((region): region is string => region !== null);
  return Array.from(new Set(regions)).sort((a, b) => a.localeCompare(b, 'ko'));
}

// 검색+유형+지역+등급 AND 조합 필터. 등급 필터를 선택하면 initialGrade가 null인 시설물은
// 어떤 등급에도 해당하지 않으므로 결과에서 빠진다(#810 명시 동작 — 필터 미적용일 때만 노출).
export function filterFacilities(
  facilities: Facility[],
  filters: FacilityListFilters,
): Facility[] {
  const searchTerm = filters.search.trim().toLowerCase();

  return facilities.filter((facility) => {
    if (searchTerm && !facility.name.toLowerCase().includes(searchTerm)) {
      return false;
    }
    if (filters.type && facility.type !== filters.type) {
      return false;
    }
    if (filters.region && parseRegionFromAddress(facility.address) !== filters.region) {
      return false;
    }
    if (filters.grade && facility.initialGrade !== filters.grade) {
      return false;
    }
    return true;
  });
}
