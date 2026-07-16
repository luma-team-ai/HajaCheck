import type { FacilityLocation } from '../types';

/**
 * 시설물 목록을 검색어와 카테고리를 기준으로 필터링합니다.
 * 대소문자를 구분하지 않고 검색합니다.
 *
 * @param facilities 전체 시설물 목록
 * @param searchQuery 검색어 (이름 또는 주소 매칭)
 * @param selectedCategory 선택된 카테고리 필터 (전체 또는 개별 카테고리)
 */
export function filterFacilities(
  facilities: FacilityLocation[] | undefined,
  searchQuery: string,
  selectedCategory: string
): FacilityLocation[] {
  if (!facilities) return [];

  const query = searchQuery.trim().toLowerCase();

  return facilities.filter((facility) => {
    const matchesSearch =
      query.length === 0 ||
      facility.name.toLowerCase().includes(query) ||
      facility.address.toLowerCase().includes(query);

    const matchesCategory = selectedCategory === '전체' || facility.category === selectedCategory;

    return matchesSearch && matchesCategory;
  });
}
