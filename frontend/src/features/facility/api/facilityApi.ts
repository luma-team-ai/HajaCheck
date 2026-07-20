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
  // 점검 주기 설정(dev-04-03, FR-019) — 저장 버튼만 실 API로 연결(handoff §2·§3)
  setSchedule: (id: number, body: SetFacilityScheduleRequest) =>
    api.post<SetFacilityScheduleResponse>(`/facilities/${id}/schedule`, body),
};
