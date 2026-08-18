// 시설물 위치 조회 — feature별 api 모듈 (React_코드_컨벤션.md §3)
// 백엔드 GET /api/facilities/map 연동 (#1656/#1657) — 기존에는 CRUD용 GET /facilities(500건 상한,
// FACILITY_LIST_MAX)를 재사용해 500건 초과 회사는 지도 마커가 무통보로 누락됐다(#1656 P1). 지도
// 전용 경량 엔드포인트로 전환해 상한을 없앤다. #1656 이슈 본문의 계약(2026-08-18 기준):
// `{ facilities: [{id,name,latitude,longitude,facilityType,highestGrade,warningCount,cautionCount,
// thumbnailUrl}] }` — 기존 목록 응답과 필드명은 동일하지만, 지도에 불필요한 필드(address 등)는
// 프로젝션에서 빠진다. 백엔드 PR(#1656)이 아직 dev에 머지되지 않아 이 계약 기준으로 msw mock으로만
// 검증했다 — 백엔드 머지 후 실응답과 어긋나면 mapApi.test.ts/mapApi.handlers.ts를 함께 갱신한다.
import { api } from '../../../shared/api/axios';
import type { FacilityLocation } from '../types';

interface FacilityMapItem {
  id: number;
  name: string;
  latitude: number | null;
  longitude: number | null;
  facilityType: string | null;
  highestGrade: FacilityLocation['highestGrade'];
  warningCount: number | null;
  cautionCount: number | null;
  thumbnailUrl: string | null;
}

interface FacilityMapResponse {
  facilities: FacilityMapItem[];
}

// 지도 화면(MapPage)과 좌표 일괄 보정 버튼(BackfillGeocodeButton)이 보정 완료 후 같은 쿼리를
// 무효화(invalidate)해야 하므로 쿼리 키를 단일 소스로 export한다(#1657) — 문자열 리터럴을 두 곳에서
// 따로 적으면 오타로 무효화가 조용히 실패할 수 있다.
export const MAP_FACILITIES_QUERY_KEY = ['map', 'facilities'] as const;

export const mapApi = {
  getFacilityLocations: async (): Promise<FacilityLocation[]> => {
    const res = await api.get<FacilityMapResponse>('/facilities/map');
    const facilities = res.data?.facilities ?? [];
    // 좌표가 없는(NULL) 시설물도 목록에는 남긴다(#1657) — 목록 패널이 '좌표 없음' 배지로 표시하고,
    // 지도 마커 생성(MapPage)에서만 isValidCoordinate/hasValidCoordinate로 걸러낸다. 예전처럼 여기서
    // null 좌표를 미리 필터링하면 "목록엔 있는데 마커는 없다"의 반대 문제(목록에서도 사라짐)가 된다.
    return facilities.map((f) => ({
      id: f.id,
      name: f.name,
      // GET /api/facilities/map 응답에는 address 필드가 없다(경량 프로젝션 계약) — 목록 패널은
      // null을 "주소 정보 없음"으로 폴백 표시한다.
      address: null,
      category: f.facilityType ?? '기타',
      latitude: f.latitude != null ? Number(f.latitude) : null,
      longitude: f.longitude != null ? Number(f.longitude) : null,
      highestGrade: f.highestGrade ?? null,
      warningCount: f.warningCount ?? 0,
      cautionCount: f.cautionCount ?? 0,
      thumbnailUrl: f.thumbnailUrl ?? null,
    }));
  },
};
