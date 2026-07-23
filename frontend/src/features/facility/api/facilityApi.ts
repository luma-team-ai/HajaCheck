import { api } from '../../../shared/api/axios';
import type {
  CreateFacilityRequest,
  Facility,
  SetFacilityScheduleRequest,
  SetFacilityScheduleResponse,
} from '../types';

export const facilityApi = {
  getList: () => api.get<Facility[]>('/facilities'),
  getDetail: (id: number) => api.get<Facility>(`/facilities/${id}`),
  create: (body: CreateFacilityRequest) => api.post<Facility>('/facilities', body),
  // 시설물 수정(PUT — 전체 교체, backend FacilityUpdateRequest와 1:1). 좌표 소급 재-geocoding(#618)이
  // 기존 필드를 유지한 채 latitude/longitude만 갱신하는 용도로 이 API를 재사용한다.
  update: (id: number, body: CreateFacilityRequest) => api.put<Facility>(`/facilities/${id}`, body),
  // 점검 주기 설정(dev-04-03, FR-019) — 저장 버튼만 실 API로 연결(handoff §2·§3)
  setSchedule: (id: number, body: SetFacilityScheduleRequest) =>
    api.post<SetFacilityScheduleResponse>(`/facilities/${id}/schedule`, body),
};
