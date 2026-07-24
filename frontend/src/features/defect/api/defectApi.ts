import { aiClient } from '../../../shared/api/aiClient';
import { api } from '../../../shared/api/axios';
import type { PageResponse } from '../../../shared/api/types';
import type {
  Defect,
  DefectActionSubmitRequest,
  DefectListFilters,
  DefectRevision,
  DefectStatus,
  InspectionFacilityOption,
  InspectionListFilters,
  InspectionListItem,
} from '../types';
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

  // --- 하자 목록·상세 개편 (HAJA-393/394, #725/#726) ---------------------------------------

  // GET /api/inspections — 점검(Inspection) 단위 목록. 백엔드 신규 구현 대기 상태라 MSW 목으로
  // 우선 개발한다(contract.md §엔드포인트 매핑 ①).
  getInspections: (filters: InspectionListFilters = {}) =>
    api.get<PageResponse<InspectionListItem>>('/inspections', { params: filters }),
  // GET /api/inspections/{id}/defects — 점검별 하자 카드 목록(카드형 상세, contract.md §②).
  // inspection feature의 inspectionApi.getDefects와 동일 엔드포인트를 defect feature 안에 자체
  // 복제해서 호출한다(feature 간 직접 import 금지, React_코드_컨벤션.md §1).
  getByInspection: (inspectionId: number) => api.get<Defect[]>(`/inspections/${inspectionId}/defects`),
  // GET /api/facilities — 점검 목록 필터의 시설물 select 옵션. facility/inspection feature import
  // 없이 실 엔드포인트만 재사용(이미 다른 feature도 동일 엔드포인트를 각자 호출하는 기존 패턴과 동일).
  listFacilityOptions: () => api.get<InspectionFacilityOption[]>('/facilities'),
  // PATCH /api/defects/{id}/status 확장 가정(BE 판단 대기, contract.md §"조치 결과 등록" 참고) —
  // 하자 상세 모달의 "조치 완료 등록" 제출 시 상태 전이(RESOLVED)와 조치결과 필드를 함께 보낸다.
  // 실제 필드명/엔드포인트가 BE 확정과 다르면 PR에 [CONTRACT-CHANGE-REQUEST]로 표시할 것.
  submitAction: (id: number, body: DefectActionSubmitRequest) =>
    api.patch<Defect>(`/defects/${id}/status`, body),
};
