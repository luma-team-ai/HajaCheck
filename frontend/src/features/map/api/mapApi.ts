// 시설물 위치 조회 — feature별 api 모듈 (React_코드_컨벤션.md §3)
// TODO(#8): 시설물 API 완성 시 /api/facilities 연동으로 교체 (현재는 목 데이터 반환)
import type { FacilityLocation } from '../types';

const MOCK_FACILITY_LOCATIONS: FacilityLocation[] = [
  {
    id: 1,
    name: '강남 오피스 빌딩',
    latitude: 37.4979,
    longitude: 127.0276,
    highestGrade: 'RED',
  },
  {
    id: 2,
    name: '여의도 복합상가',
    latitude: 37.5219,
    longitude: 126.9245,
    highestGrade: 'YELLOW',
  },
  {
    id: 3,
    name: '종로 문화센터',
    latitude: 37.5729,
    longitude: 126.9793,
    highestGrade: 'GREEN',
  },
  {
    id: 4,
    name: '성동 물류창고',
    latitude: 37.5636,
    longitude: 127.0369,
    highestGrade: 'YELLOW',
  },
  {
    id: 5,
    name: '송파 주상복합',
    latitude: 37.5145,
    longitude: 127.1058,
    highestGrade: 'GREEN',
  },
];

export const mapApi = {
  getFacilityLocations: async (): Promise<FacilityLocation[]> => {
    // TODO(#8): 백엔드 /api/facilities 연동 시 아래 목 데이터 제거하고
    // return api.get<FacilityLocation[]>('/facilities').then((res) => res.data);
    return Promise.resolve(MOCK_FACILITY_LOCATIONS);
  },
};
