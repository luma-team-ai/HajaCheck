// 시설물 위치 조회 — feature별 api 모듈 (React_코드_컨벤션.md §3)
// TODO(#8): 시설물 API 완성 시 /api/facilities 연동으로 교체 (현재는 목 데이터 반환)
import type { FacilityLocation } from '../types';

const MOCK_FACILITY_LOCATIONS: FacilityLocation[] = [
  {
    id: 1,
    name: '한강대교 북단',
    address: '서울 용산구 이촌동 302-14',
    category: '교량',
    latitude: 37.5145,
    longitude: 126.9631,
    highestGrade: 'E',
    warningCount: 12,
    cautionCount: 5,
    thumbnailUrl: null,
  },
  {
    id: 2,
    name: '남산1호터널',
    address: '서울 중구 예장동',
    category: '터널',
    latitude: 37.5559,
    longitude: 126.9939,
    highestGrade: 'C',
    warningCount: 3,
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
