import { api } from '../../../shared/api/axios';
import type {
  FacilityDetail,
  FacilityOption,
  InspectionCreateRequest,
  InspectionCreateResponse,
} from '../types';
import type { InspectionResponse, DefectDetailItem, DefectGrade } from './inspectionApi.types';

export interface DefectRevisionRequest {
  grade?: DefectGrade;
  isDeleted?: boolean;
  reason?: string;
}

export const inspectionApi = {
  // 점검 회차 조회
  getInspection: (inspectionId: number) =>
    api.get<InspectionResponse>(`/inspections/${inspectionId}`),
  // 점검 회차별 하자 목록 조회
  getDefects: (inspectionId: number) =>
    api.get<DefectDetailItem[]>(`/inspections/${inspectionId}/defects`),
  // 하자 검수: 오탐 삭제 또는 등급 조정 (DefectRevisionController.reviewDefect)
  reviewDefect: (defectId: number, request: DefectRevisionRequest) =>
    api.patch<DefectDetailItem>(`/defects/${defectId}`, request),
  // 점검(회차) 생성
  create: (body: InspectionCreateRequest) =>
    api.post<InspectionCreateResponse>('/inspections', body),
  // 점검(회차) 생성 폼의 시설물 셀렉트용 — 실 GET /api/facilities(FacilityController.list)를 그대로 호출한다.
  listFacilityOptions: () => api.get<FacilityOption[]>('/facilities'),
  // 점검(회차) 생성 화면 상단 개요 패널용 — 실 GET /api/facilities/{id}(FacilityController.get)를 그대로 호출한다.
  getFacilityDetail: (id: number) => api.get<FacilityDetail>(`/facilities/${id}`),
};
