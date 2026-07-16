// 시설물 위치 조회 — feature별 api 모듈 (React_코드_컨벤션.md §3)
// TODO(#8): 시설물 API 완성 시 /api/facilities 연동으로 교체 (현재는 목 데이터 반환)
import type { FacilityLocation } from '../types';

const MOCK_FACILITY_LOCATIONS: FacilityLocation[] = [
  {
    id: 1,
    name: '강남 오피스 빌딩',
    address: '서울 강남구 테헤란로 123',
    category: '건물',
    latitude: 37.4979,
    longitude: 127.0276,
    highestGrade: 'E',
    warningCount: 3,
    cautionCount: 5,
    thumbnailUrl: null,
  },
  {
    id: 2,
    name: '여의도 복합상가',
    address: '서울 영등포구 여의대로 24',
    category: '건물',
    latitude: 37.5219,
    longitude: 126.9245,
    highestGrade: 'C',
    warningCount: 0,
    cautionCount: 2,
    thumbnailUrl: null,
  },
  {
    id: 3,
    name: '종로 문화센터',
    address: '서울 종로구 종로 99',
    category: '건물',
    latitude: 37.5729,
    longitude: 126.9793,
    highestGrade: 'A',
    warningCount: 0,
    cautionCount: 0,
    thumbnailUrl: null,
  },
  {
    id: 4,
    name: '성동 물류창고',
    address: '서울 성동구 성수동 12',
    category: '건물',
    latitude: 37.5636,
    longitude: 127.0369,
    highestGrade: 'D',
    warningCount: 1,
    cautionCount: 3,
    thumbnailUrl: null,
  },
  {
    id: 5,
    name: '한강대교 북단',
    address: '서울 용산구 한강대로 인근',
    category: '교량',
    latitude: 37.5145,
    longitude: 127.1058,
    highestGrade: 'B',
    warningCount: 0,
    cautionCount: 1,
    thumbnailUrl: null,
  },
];

export const mapApi = {
  getFacilityLocations: async (): Promise<FacilityLocation[]> => {
    // TODO(#8): 백엔드 /api/facilities 연동 시 아래 목 데이터 제거하고
    // return api.get<FacilityLocation[]>('/facilities').then((res) => res.data);
    return Promise.resolve(MOCK_FACILITY_LOCATIONS);
  },
};
