import { api } from '../../../shared/api/axios';
import type { CreateFacilityRequest, Facility } from '../types';

export const facilityApi = {
  getList: () => api.get<Facility[]>('/facilities'),
  getDetail: (id: number) => api.get<Facility>(`/facilities/${id}`),
  create: (body: CreateFacilityRequest) => api.post<Facility>('/facilities', body),
};
