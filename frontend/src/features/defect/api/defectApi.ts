import { aiClient } from '../../../shared/api/aiClient';
import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type { Defect, DefectListFilters, DefectStatus } from '../types';
import type { NlSearchResult } from '../nlSearchTypes';

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
  // POST /api/defects/nl-search — 자연어 검색 → 필터 조건 변환. 세션 인증·점검자 역할·AI 부가 기능
  // 게이트가 걸린 공개 Spring 게이트웨이라 aiClient가 아니라 일반 백엔드 클라이언트(api)를 사용한다
  // (docs/design/ai/nl_search_filter_schema.md §4 — "프론트도 aiClient가 아니라... Spring API 클라이언트 사용").
  nlSearch: (query: string) => api.post<NlSearchResult>('/defects/nl-search', { query }),
};
