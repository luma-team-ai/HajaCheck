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
    // address는 nullable이다(#1656 계약 — 주소 미입력 시설물이 있을 수 있음) — null이면 검색어
    // 매칭 대상에서 빈 문자열로 취급한다(name 매칭만으로 판정, 예외 없이 동작해야 한다).
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
