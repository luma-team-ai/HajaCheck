/**
 * "건물-정기-4개월" 같은 복합 시설물 유형 문자열에서 카테고리 접두어를 추출한다.
 * 단일 값("건물", "기타")은 그대로 반환한다.
 *
 * @param facilityType - 시설물 유형 문자열 (null/undefined 시 빈 문자열)
 */
export function extractFacilityCategory(facilityType: string | null | undefined): string {
  if (facilityType == null) return '';
  if (facilityType === '') return '';
  return facilityType.split('-')[0];
}
