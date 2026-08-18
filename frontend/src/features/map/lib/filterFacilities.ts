import { extractFacilityCategory } from '../../../shared/lib/facilityCategory';
import type { FacilityLocation } from '../types';

export function filterFacilities(
  facilities: FacilityLocation[] | undefined,
  searchQuery: string,
  selectedCategory: string
): FacilityLocation[] {
  if (!facilities) return [];

  const query = searchQuery.trim().toLowerCase();

  return facilities.filter((facility) => {
    // address는 GET /api/facilities/map 응답에 없어 null일 수 있다(#1657, 경량 프로젝션 계약) —
    // null이면 검색어 매칭 대상에서 빈 문자열로 취급한다(name 매칭만으로 판정).
    const matchesSearch =
      query.length === 0 ||
      facility.name.toLowerCase().includes(query) ||
      (facility.address ?? '').toLowerCase().includes(query);

    const matchesCategory =
      selectedCategory === '전체' ||
      extractFacilityCategory(facility.category) === selectedCategory;

    return matchesSearch && matchesCategory;
  });
}
