import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockFacilityAssignableUsers } from '../mocks/facilityAssignee.mock';
import type { FacilityAssignableUser } from '../types';

// 담당자 select 옵션 MSW 목 — 실 API 없음(facilityAssigneeApi.ts 주석 참고). 후속 실 API 연동 시
// 이 핸들러를 걷어낸다.
export const facilityAssigneeHandlers = [
  http.get('/api/facilities/assignable-users', () => {
    const body: ApiResponse<FacilityAssignableUser[]> = {
      success: true,
      data: mockFacilityAssignableUsers,
    };
    return HttpResponse.json(body);
  }),
];
