import type { FacilityTypeCategory } from '../types';

// facilities.type 저장값은 "건물-정기-4개월" 같은 {종류}-{점검유형}-{주기} 복합 문자열이다
// (features/facility/constants.ts FACILITY_TYPE_OPTIONS, #731). 히트맵(시설물 유형 × 월별,
// PRD §3.2 C안)은 접두어(종류)만 카테고리로 쓴다 — PRD §3.3.
const KNOWN_CATEGORIES: readonly FacilityTypeCategory[] = ['건물', '교량', '도로', '기타'];

/**
 * facilities.type 원본 문자열에서 시설물 유형 카테고리(접두어)를 추출한다.
 * 알려진 4종("건물"/"교량"/"도로"/"기타") 중 하나와 일치하지 않으면(과거 데이터 등) '기타'로 묶는다.
 */
export function parseFacilityTypeCategory(rawType: string): FacilityTypeCategory {
  const prefix = rawType.split('-')[0]?.trim();
  const matched = KNOWN_CATEGORIES.find((category) => category === prefix);
  return matched ?? '기타';
}
