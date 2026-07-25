import { describe, expect, it } from 'vitest';
import type { Facility } from '../types';
import {
  filterFacilities,
  getFacilityRegionOptions,
  getFacilityTypeOptions,
  parseRegionFromAddress,
} from './filterFacilities';

function buildFacility(overrides: Partial<Facility>): Facility {
  return {
    id: 1,
    name: '강남 오피스타워 A동',
    type: '건물',
    address: '서울 강남구 테헤란로 123',
    latitude: null,
    longitude: null,
    builtYear: null,
    scale: null,
    inspectionCycleMonths: null,
    nextInspectionDueAt: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    initialGrade: null,
    assigneeUserId: null,
    memo: null,
    ...overrides,
  };
}

describe('parseRegionFromAddress', () => {
  it('주소 첫 토큰(공백 기준)을 지역으로 반환한다', () => {
    expect(parseRegionFromAddress('서울 강남구 테헤란로 123')).toBe('서울');
    expect(parseRegionFromAddress('경기 성남시 분당구 판교역로 235')).toBe('경기');
  });

  it('address가 null이면 null을 반환한다(에러 없이 안전 처리)', () => {
    expect(parseRegionFromAddress(null)).toBeNull();
  });

  it('address가 빈 문자열이면 null을 반환한다', () => {
    expect(parseRegionFromAddress('')).toBeNull();
    expect(parseRegionFromAddress('   ')).toBeNull();
  });

  it('다음 우편번호 API가 반환하는 정식 행정구역 접미사를 정규화한다(#825)', () => {
    // 실제 주소 입력은 자유 텍스트가 아니라 다음 우편번호 roadAddress/jibunAddress로만 들어오므로
    // "서울" 같은 축약형이 아니라 "서울특별시"처럼 전체 명칭이 온다.
    expect(parseRegionFromAddress('서울특별시 강남구 테헤란로 152')).toBe('서울');
    expect(parseRegionFromAddress('경기도 성남시 분당구 판교역로 235')).toBe('경기');
    expect(parseRegionFromAddress('부산광역시 해운대구 센텀중앙로 79')).toBe('부산');
    expect(parseRegionFromAddress('세종특별자치시 한누리대로 2130')).toBe('세종');
    expect(parseRegionFromAddress('제주특별자치도 제주시 첨단로 242')).toBe('제주');
  });

  it('접미사가 없는 축약형 주소는 그대로 유지한다(기존 목데이터/#810 스펙 회귀 방지)', () => {
    expect(parseRegionFromAddress('서울 강남구 테헤란로 123')).toBe('서울');
  });
});

describe('getFacilityTypeOptions', () => {
  it('로드된 시설물 목록에서 distinct 유형을 가나다순으로 반환한다', () => {
    const facilities = [
      buildFacility({ id: 1, type: '교량' }),
      buildFacility({ id: 2, type: '건물' }),
      buildFacility({ id: 3, type: '건물' }),
    ];

    expect(getFacilityTypeOptions(facilities)).toEqual(['건물', '교량']);
  });
});

describe('getFacilityRegionOptions', () => {
  it('address가 있는 시설물에서만 distinct 지역을 구성한다(null address 제외)', () => {
    const facilities = [
      buildFacility({ id: 1, address: '서울 강남구 테헤란로 123' }),
      buildFacility({ id: 2, address: '경기 성남시 분당구 판교역로 235' }),
      buildFacility({ id: 3, address: null }),
    ];

    expect(getFacilityRegionOptions(facilities)).toEqual(['경기', '서울']);
  });

  it('접미사 포함/미포함 주소가 섞여 있어도 같은 지역으로 정규화되어 중복 옵션이 생기지 않는다(#825)', () => {
    const facilities = [
      buildFacility({ id: 1, address: '서울 강남구 테헤란로 123' }),
      buildFacility({ id: 2, address: '서울특별시 서초구 서초대로 1' }),
      buildFacility({ id: 3, address: '경기도 성남시 분당구 판교역로 235' }),
    ];

    expect(getFacilityRegionOptions(facilities)).toEqual(['경기', '서울']);
  });
});

describe('filterFacilities', () => {
  const facilities: Facility[] = [
    buildFacility({
      id: 1,
      name: '강남 오피스타워 A동',
      type: '건물',
      address: '서울 강남구 테헤란로 123',
      initialGrade: 'B',
    }),
    buildFacility({
      id: 2,
      name: '판교 테크노밸리 B동',
      type: '건물',
      address: '경기 성남시 분당구 판교역로 235',
      initialGrade: null,
    }),
    buildFacility({
      id: 3,
      name: '한강대교 북단',
      type: '교량',
      address: null,
      initialGrade: 'A',
    }),
  ];

  it('필터가 전부 비어있으면 전체 목록을 그대로 반환한다', () => {
    expect(filterFacilities(facilities, { search: '', type: '', region: '', grade: '' })).toEqual(
      facilities,
    );
  });

  it('시설물명 부분일치·대소문자 무시로 검색한다', () => {
    const result = filterFacilities(facilities, {
      search: '오피스',
      type: '',
      region: '',
      grade: '',
    });
    expect(result.map((f) => f.id)).toEqual([1]);
  });

  it('검색+유형을 동시에 적용하면 AND 조합으로 좁혀진다', () => {
    const result = filterFacilities(facilities, {
      search: '동',
      type: '건물',
      region: '',
      grade: '',
    });
    expect(result.map((f) => f.id)).toEqual([1, 2]);

    const narrower = filterFacilities(facilities, {
      search: '판교',
      type: '건물',
      region: '',
      grade: '',
    });
    expect(narrower.map((f) => f.id)).toEqual([2]);
  });

  it('지역 필터는 address가 null인 시설물을 에러 없이 결과에서 제외한다', () => {
    const result = filterFacilities(facilities, {
      search: '',
      type: '',
      region: '서울',
      grade: '',
    });
    expect(result.map((f) => f.id)).toEqual([1]);
  });

  it('등급 필터를 선택하면 initialGrade가 null인 시설물은 제외된다', () => {
    const result = filterFacilities(facilities, {
      search: '',
      type: '',
      region: '',
      grade: 'A',
    });
    expect(result.map((f) => f.id)).toEqual([3]);
  });

  it('등급 필터를 선택하지 않으면 initialGrade가 null인 시설물도 노출된다', () => {
    const result = filterFacilities(facilities, { search: '', type: '', region: '', grade: '' });
    expect(result.map((f) => f.id)).toEqual([1, 2, 3]);
  });

  it('검색+유형+지역+등급을 모두 동시에 적용할 수 있다', () => {
    const result = filterFacilities(facilities, {
      search: '강남',
      type: '건물',
      region: '서울',
      grade: 'B',
    });
    expect(result.map((f) => f.id)).toEqual([1]);
  });
});
