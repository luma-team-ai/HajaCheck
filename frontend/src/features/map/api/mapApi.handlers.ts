import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockFacilityMapLocations } from '../mocks/facilityMapLocation.mock';

// GET /api/facilities/map(#1656/#1657) 목 핸들러 — 백엔드 PR(#1656)이 아직 dev에 머지되지 않아, 순수
// mock/dev 모드(src/mocks/handlers.ts, hybridMode=false)에서도 지도 화면이 계속 동작하도록 등록한다.
// hybrid 모드(실 백엔드 연동)에서는 handlers.ts가 이 핸들러 자체를 등록하지 않으므로 실 API로 그대로
// 통과한다.
export const mapHandlers = [
  http.get('/api/facilities/map', () => {
    const body: ApiResponse<{ facilities: typeof mockFacilityMapLocations }> = {
      success: true,
      data: { facilities: mockFacilityMapLocations },
    };
    return HttpResponse.json(body);
  }),
];
