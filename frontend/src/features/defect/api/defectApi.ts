import { aiClient } from '../../../shared/api/aiClient';
import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type { Defect, DefectListFilters, DefectRevision, DefectStatus } from '../types';

type DefectExplainRequest = {
  defect_type: string;
  severity_grade: string;
  location: string;
  facility_type: string;
};

type DefectExplain = {
  cause: string;
  risk: string;
  action: string;
};

export const defectApi = {
  // POST /api/ai/defect-explain — AI 하자 원인·조치방안 설명
  getExplanation: (req: DefectExplainRequest) =>
    aiClient.post<DefectExplain>('/defect-explain', req),
  // GET /api/defects — 내 하자 목록(유형/등급/상태 필터 + 페이지네이션), 일반 백엔드 클라이언트(api) 사용
  getList: (filters: DefectListFilters = {}) =>
    api.get<PageResponse<Defect>>('/defects', { params: filters }),
  // GET /api/defects/{id} — 하자 상세
  getDetail: (id: number) => api.get<Defect>(`/defects/${id}`),
  // PATCH /api/defects/{id}/status — 하자 상태 전이(신규→검수확정→조치대기→조치중→조치완료 순서만 허용)
  updateStatus: (id: number, status: DefectStatus) =>
    api.patch<Defect>(`/defects/${id}/status`, { status }),
  // GET /api/defects/{id}/revisions — 하자 활동 기록(상태 변경 이력) 페이지 조회
  getRevisions: (id: number, page = 0) =>
    api.get<PageResponse<DefectRevision>>(`/defects/${id}/revisions`, { params: { page } }),
};
