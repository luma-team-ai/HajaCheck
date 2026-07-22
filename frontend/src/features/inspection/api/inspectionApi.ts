import { api } from '../../../shared/api/axios';
import type {
  FacilityDetail,
  FacilityOption,
  InspectionCreateRequest,
  InspectionCreateResponse,
  InspectionResult,
} from '../types';

export const inspectionApi = {
  getResult: (inspectionId: number) =>
    api.get<InspectionResult>(`/inspections/${inspectionId}/result`),
  create: (body: InspectionCreateRequest) =>
    api.post<InspectionCreateResponse>('/inspections', body),
  // 점검(회차) 생성 폼의 시설물 셀렉트용 — 실 GET /api/facilities(FacilityController.list)를 그대로 호출한다.
  listFacilityOptions: () => api.get<FacilityOption[]>('/facilities'),
  // 점검(회차) 생성 화면 상단 개요 패널용 — 실 GET /api/facilities/{id}(FacilityController.get)를 그대로 호출한다.
  getFacilityDetail: (id: number) => api.get<FacilityDetail>(`/facilities/${id}`),
};
