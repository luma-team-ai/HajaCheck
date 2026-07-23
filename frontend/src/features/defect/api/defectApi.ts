import { aiClient } from '../../../shared/api/aiClient';
import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type { Defect, DefectListFilters, DefectRevision, DefectStatus } from '../types';
import type { NlSearchResult } from '../nlSearchTypes';

// DefectController.getRevisions @PageableDefault(size=20)과 반드시 일치시킬 것.
export const DEFECT_REVISIONS_PAGE_SIZE = 20;

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
  // PATCH /api/defects/{id}/status — 하자 상태 전이. 정방향 1단계는 reason 없이 허용되고, 역행·건너뛰기
  // 전이는 reason이 없으면 400(INVALID_INPUT)이다(조치 보드 드래그 전이, HAJA-349/#630). reason이 없을 때는
  // 아예 body에서 필드를 빼서(값 undefined를 보내지 않음) 백엔드 Bean Validation과의 불필요한 충돌을 피한다.
  updateStatus: (id: number, status: DefectStatus, reason?: string) =>
    api.patch<Defect>(`/defects/${id}/status`, reason != null ? { status, reason } : { status }),
  // GET /api/defects/{id}/revisions — 하자 활동 기록(상태 변경 이력) 페이지 조회.
  // size는 DefectController.getRevisions의 @PageableDefault(size=20)와 일치시켜 명시 전달한다 —
  // 역행/건너뛰기 전이가 반복되면 이력 건수가 4단계로 고정되지 않아(self-review 발견) 프론트가
  // 몇 건씩 요청하는지 암묵적으로 백엔드 기본값에 기대지 않기 위함.
  getRevisions: (id: number, page = 0, size = DEFECT_REVISIONS_PAGE_SIZE) =>
    api.get<PageResponse<DefectRevision>>(`/defects/${id}/revisions`, { params: { page, size } }),
  // POST /api/defects/nl-search — 자연어 검색 → 필터 조건 변환. 세션 인증·점검자 역할·AI 부가 기능
  // 게이트가 걸린 공개 Spring 게이트웨이라 aiClient가 아니라 일반 백엔드 클라이언트(api)를 사용한다
  // (docs/design/ai/nl_search_filter_schema.md §4 — "프론트도 aiClient가 아니라... Spring API 클라이언트 사용").
  nlSearch: (query: string) => api.post<NlSearchResult>('/defects/nl-search', { query }),
};
