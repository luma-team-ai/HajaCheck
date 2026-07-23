import { api } from '../../../shared/api/axios';
import type {
  FacilityDetail,
  FacilityOption,
  InspectionCreateRequest,
  InspectionCreateResponse,
} from '../types';
import type {
  InspectionResponse,
  DefectDetailItem,
  DefectGrade,
  DefectStatus,
  DefectCreateRequest,
  AnalysisStatusResponse,
} from './inspectionApi.types';

export interface DefectRevisionRequest {
  grade?: DefectGrade;
  isDeleted?: boolean;
  reason?: string;
}

export interface DefectStatusUpdateRequest {
  status: DefectStatus;
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
  // 하자 상태 전환 (DefectController.updateStatus)
  updateDefectStatus: (defectId: number, request: DefectStatusUpdateRequest) =>
    api.patch<DefectDetailItem>(`/defects/${defectId}/status`, request),
  // 하자 수동 생성 — AI가 놓친 하자를 검수자가 추가 (DefectRevisionController.createManualDefect)
  createDefect: (inspectionId: number, request: DefectCreateRequest) =>
    api.post<DefectDetailItem>(`/inspections/${inspectionId}/defects`, request),
  // 점검(회차) 생성
  create: (body: InspectionCreateRequest) =>
    api.post<InspectionCreateResponse>('/inspections', body),
  // 점검(회차) 생성 폼의 시설물 셀렉트용 — 실 GET /api/facilities(FacilityController.list)를 그대로 호출한다.
  listFacilityOptions: () => api.get<FacilityOption[]>('/facilities'),
  // 분석 결과 뷰어(useInspectionResultReal)용 — 실 GET /api/facilities/{id}(FacilityController.get)를 그대로 호출한다.
  getFacilityDetail: (id: number) => api.get<FacilityDetail>(`/facilities/${id}`),
  // AI 분석 실행/상태(dev-05-04) — 분석 시작(202 Accepted, 바로 반환) + 진행 상태 폴링.
  startAnalysis: (inspectionId: number) => api.post<void>(`/inspections/${inspectionId}/analyze`),
  getAnalysisStatus: (inspectionId: number) =>
    api.get<AnalysisStatusResponse>(`/inspections/${inspectionId}/analyze`),
};
