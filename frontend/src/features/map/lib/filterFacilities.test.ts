import { describe, expect, it } from 'vitest';
import type { FacilityLocation } from '../types';
import { filterFacilities } from './filterFacilities';

const mockFacilities: FacilityLocation[] = [
  {
    id: 1,
    name: '아크로빌 빌딩',
    address: '서울특별시 강남구 역삼동 123',
    category: '오피스',
    latitude: 37.123,
    longitude: 127.123,
    highestGrade: 'A',
    warningCount: 0,
    cautionCount: 1,
    thumbnailUrl: null,
  },
  {
    id: 2,
    name: 'Sunny Apartment',
    address: '경기도 성남시 분당구 삼평동 456',
    category: '공동주택',
    latitude: 37.456,
    longitude: 127.456,
    highestGrade: 'C',
    warningCount: 2,
    cautionCount: 3,
    thumbnailUrl: null,
  },
  {
    id: 3,
    name: '강남 타워',
    address: '서울특별시 강남구 서초동 789',
    category: '오피스',
    latitude: 37.789,
    longitude: 127.789,
    highestGrade: 'E',
    warningCount: 5,
    cautionCount: 0,
    thumbnailUrl: null,
  },
];

describe('filterFacilities', () => {
  it('facilities가 undefined이면 빈 배열을 반환한다', () => {
    const result = filterFacilities(undefined, '', '전체');
    expect(result).toEqual([]);
  });

  it('빈 검색어와 전체 카테고리 필터일 때 전체 목록을 반환한다', () => {
    const result = filterFacilities(mockFacilities, '', '전체');
    expect(result).toHaveLength(3);
    expect(result).toEqual(mockFacilities);
  });

  it('시설물 이름으로 검색하여 올바른 결과를 반환한다', () => {
    const result = filterFacilities(mockFacilities, '아크로빌', '전체');
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe(1);
  });

  it('시설물 주소로 검색하여 올바른 결과를 반환한다', () => {
    const result = filterFacilities(mockFacilities, '분당구', '전체');
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe(2);
  });

  it('대소문자를 구분하지 않고 영어 이름을 검색할 수 있다', () => {
    const resultUpper = filterFacilities(mockFacilities, 'SUNNY', '전체');
    const resultLower = filterFacilities(mockFacilities, 'sunny', '전체');
    expect(resultUpper).toHaveLength(1);
    expect(resultUpper[0].id).toBe(2);
    expect(resultLower).toHaveLength(1);
    expect(resultLower[0].id).toBe(2);
  });

  it('카테고리 필터가 정상 작동한다', () => {
    const result = filterFacilities(mockFacilities, '', '오피스');
    expect(result).toHaveLength(2);
    expect(result.map((f) => f.id)).toEqual([1, 3]);
  });

  it('검색어와 카테고리 필터가 모두 매칭되어야 필터링된다', () => {
    const resultMatch = filterFacilities(mockFacilities, '강남', '오피스');
    expect(resultMatch).toHaveLength(2); // '아크로빌 빌딩' (역삼동 강남구 주소 매칭), '강남 타워' (명칭 매칭)

    const resultMismatch = filterFacilities(mockFacilities, '아크로빌', '공동주택');
    expect(resultMismatch).toHaveLength(0); // 아크로빌은 오피스 카테고리이므로 불일치
  });
});
