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
    const matchesSearch =
      query.length === 0 ||
      facility.name.toLowerCase().includes(query) ||
      facility.address.toLowerCase().includes(query);

    const matchesCategory =
      selectedCategory === '전체' ||
      extractFacilityCategory(facility.category) === selectedCategory;

    return matchesSearch && matchesCategory;
  });
}
