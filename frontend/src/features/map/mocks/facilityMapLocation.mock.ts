// GET /api/facilities/map(#1656/#1657) 전용 목 데이터 — facility feature의 mockFacilities와는
// 별개로 독립 구성한다(feature 간 직접 import 금지, React_코드_컨벤션.md §1 — 기존 defectApi.handlers.ts
// 주석의 "각자 복제" 관례와 동일). 지도 전용 경량 프로젝션 계약(id/name/latitude/longitude/
// facilityType/highestGrade/warningCount/cautionCount/thumbnailUrl)만 담는다.
export interface FacilityMapLocationMock {
  id: number;
  name: string;
  latitude: number | null;
  longitude: number | null;
  facilityType: string;
  highestGrade: 'A' | 'B' | 'C' | 'D' | 'E' | null;
  warningCount: number | null;
  cautionCount: number | null;
  thumbnailUrl: string | null;
}

export const mockFacilityMapLocations: FacilityMapLocationMock[] = [
  {
    id: 1,
    name: '한강대교 북단',
    latitude: 37.5215,
    longitude: 126.9576,
    facilityType: '교량',
    highestGrade: 'D',
    warningCount: 4,
    cautionCount: 2,
    thumbnailUrl: null,
  },
  {
    id: 2,
    name: '판교 테크노밸리 B동',
    latitude: 37.402056,
    longitude: 127.108212,
    facilityType: '건물',
    highestGrade: 'A',
    warningCount: 0,
    cautionCount: 1,
    thumbnailUrl: null,
  },
  {
    id: 3,
    name: '남산1호터널',
    latitude: 37.5559,
    longitude: 126.9939,
    facilityType: '터널',
    highestGrade: 'C',
    warningCount: 3,
    cautionCount: 1,
    thumbnailUrl: null,
  },
  // 좌표 미확보 시설물(#1657) — 목록 패널의 '좌표 없음' 배지 + 마커 제외 동작을 로컬 mock 모드에서도
  // 눈으로 확인할 수 있도록 하나 포함한다(EXIF GPS 결측/등록 시 지오코딩 실패 등으로 실제로도 발생).
  {
    id: 4,
    name: '좌표 미확보 소규모 창고',
    latitude: null,
    longitude: null,
    facilityType: '건물',
    highestGrade: null,
    warningCount: null,
    cautionCount: null,
    thumbnailUrl: null,
  },
];
