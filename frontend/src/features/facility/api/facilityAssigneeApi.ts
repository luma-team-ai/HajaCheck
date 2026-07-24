import { api } from '../../../shared/api/axios';
import type { FacilityAssignableUser } from '../types';

// 담당자 select 옵션 조회 — 실 API 계약 없음(2026-07-23 조사, #629). 이번 범위는 MSW 목 전용
// 엔드포인트(facilityAssigneeApi.handlers.ts)로 처리하고, 실 연동은 후속 이슈로 분리한다.
export const facilityAssigneeApi = {
  listAssignableUsers: () =>
    api.get<FacilityAssignableUser[]>('/facilities/assignable-users'),
};
