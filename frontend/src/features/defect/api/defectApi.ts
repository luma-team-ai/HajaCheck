import { aiClient } from '../../../shared/api/aiClient';
import { api } from '../../../shared/api/axios';
import { AI_TIMEOUT_MS } from '../../../shared/api/timeouts';
import type { PageResponse } from '../../../shared/api/types';
import type {
  Defect,
  DefectExportItem,
  DefectActionLogEntry,
  DefectActionLogPhase,
  DefectActionSubmitRequest,
  DefectListFilters,
  DefectRevision,
  DefectStatus,
  InspectionFacilityOption,
  InspectionDefectResponse,
  InspectionListFilters,
  InspectionListItem,
} from '../types';
import type { NlSearchResult } from '../nlSearchTypes';
import { normalizeDefect } from '../utils/normalizeDefect';
import { mapInspectionDefect } from '../utils/inspectionDefectMapper';

// DefectController.getRevisions @PageableDefault(size=20)과 반드시 일치시킬 것.
export const DEFECT_REVISIONS_PAGE_SIZE = 20;
const INSPECTION_EXPORT_PAGE_SIZE = 100;
const DEFECT_EXPORT_CONCURRENCY = 5;

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

export function toInspectionListParams(filters: InspectionListFilters) {
  const nonEmpty = <T>(values: T[] | null | undefined) =>
    values && values.length > 0 ? values : undefined;

  return {
    facilityId: filters.facilityId,
    inspectionType: nonEmpty(filters.inspectionType),
    status: nonEmpty(filters.inspectionStatus),
    inspectionDateFrom: filters.inspectionDateFrom || undefined,
    inspectionDateTo: filters.inspectionDateTo || undefined,
    roundNoMin: filters.roundNoMin ?? undefined,
    roundNoMax: filters.roundNoMax ?? undefined,
    defectCountMin: filters.defectCountMin ?? undefined,
    defectCountMax: filters.defectCountMax ?? undefined,
    defectType: nonEmpty(filters.defectType),
    defectGrade: nonEmpty(filters.defectGrade),
    defectStatus: nonEmpty(filters.defectStatus),
    page: filters.page,
    size: filters.size,
  };
}

export const defectApi = {
  // POST /api/ai/defect-explain — AI 하자 원인·조치방안 설명
  getExplanation: (req: DefectExplainRequest) =>
    aiClient.post<DefectExplain>('/defect-explain', req),
  // GET /api/defects — 내 하자 목록(유형/등급/상태 필터 + 페이지네이션), 일반 백엔드 클라이언트(api) 사용
  getList: (filters: DefectListFilters = {}) =>
    api.get<PageResponse<Defect>>('/defects', { params: filters }).then((response) => ({
      ...response,
      data: {
        ...response.data,
        content: response.data.content.map(normalizeDefect),
      },
    })),
  // GET /api/defects/{id} — 하자 상세
  getDetail: (id: number) =>
    api.get<Defect>(`/defects/${id}`).then((response) => ({
      ...response,
      data: normalizeDefect(response.data),
    })),
  // PATCH /api/defects/{id}/status — 하자 상태 전이. 정방향 1단계는 reason 없이 허용되고, 역행·건너뛰기
  // 전이는 reason이 없으면 400(INVALID_INPUT)이다(조치 보드 드래그 전이, HAJA-349/#630). reason이 없을 때는
  // 아예 body에서 필드를 빼서(값 undefined를 보내지 않음) 백엔드 Bean Validation과의 불필요한 충돌을 피한다.
  updateStatus: (id: number, status: DefectStatus, reason?: string) =>
    api
      .patch<Defect>(`/defects/${id}/status`, reason != null ? { status, reason } : { status })
      .then((response) => ({
        ...response,
        data: normalizeDefect(response.data),
      })),
  // GET /api/defects/{id}/revisions — 하자 활동 기록(상태 변경 이력) 페이지 조회.
  // size는 DefectController.getRevisions의 @PageableDefault(size=20)와 일치시켜 명시 전달한다 —
  // 역행/건너뛰기 전이가 반복되면 이력 건수가 4단계로 고정되지 않아(self-review 발견) 프론트가
  // 몇 건씩 요청하는지 암묵적으로 백엔드 기본값에 기대지 않기 위함.
  getRevisions: (id: number, page = 0, size = DEFECT_REVISIONS_PAGE_SIZE) =>
    api.get<PageResponse<DefectRevision>>(`/defects/${id}/revisions`, { params: { page, size } }),
  // POST /api/defects/nl-search — 자연어 검색 → 필터 조건 변환. 세션 인증·점검자 역할·AI 부가 기능
  // 게이트가 걸린 공개 Spring 게이트웨이라 aiClient가 아니라 일반 백엔드 클라이언트(api)를 사용한다
  // (docs/design/ai/nl_search_filter_schema.md §4 — "프론트도 aiClient가 아니라... Spring API 클라이언트 사용").
  // ⚠️ api 인스턴스를 쓰지만 실제로는 LLM 경유다 — 백엔드 NlSearchService가 ai-server를 동기로
  // 호출하므로 스프링 ai.server read-timeout 150초가 걸린다. 전역 30초면 정상 검색이 끊기므로
  // AI 상한으로 override 한다(#1598, 근거는 timeouts.ts).
  nlSearch: (query: string) =>
    api.post<NlSearchResult>('/defects/nl-search', { query }, { timeout: AI_TIMEOUT_MS }),

  // --- 하자 목록·상세 개편 (HAJA-393/394, #725/#726) ---------------------------------------

  // GET /api/inspections — 점검(Inspection) 단위 목록. 백엔드 PR #891로 구현 완료(origin/dev,
  // 2026-07-26 확인) — defectType/defectGrade/defectStatus는 Spring `@RequestParam List<T>`로
  // 바인딩되어 `key=v1&key=v2`(대괄호 없는 반복 키)를 기대한다. axios 기본 배열 직렬화는 `key[]=v1`
  // 형태를 만들 수 있어(백엔드가 빈 리스트로 받는 위험, #726/HAJA-394) paramsSerializer로 대괄호 없는
  // 반복 키 직렬화를 명시 강제한다(axios 1.x `indexes: null` 옵션).
  getInspections: (filters: InspectionListFilters = {}) =>
    api.get<PageResponse<InspectionListItem>>('/inspections', {
      params: toInspectionListParams(filters),
      paramsSerializer: { indexes: null },
    }),
  // GET /api/inspections/{id}/defects — 점검별 하자 카드 목록(카드형 상세, contract.md §②).
  // inspection feature의 inspectionApi.getDefects와 동일 엔드포인트를 defect feature 안에 자체
  // 복제해서 호출한다(feature 간 직접 import 금지, React_코드_컨벤션.md §1).
  getByInspection: (inspectionId: number) =>
    api.get<InspectionDefectResponse[]>(`/inspections/${inspectionId}/defects`).then((response) => ({
      ...response,
      data: response.data.map(mapInspectionDefect),
    })),
  // GET /api/facilities — 점검 목록 필터의 시설물 select 옵션. facility/inspection feature import
  // 없이 실 엔드포인트만 재사용(이미 다른 feature도 동일 엔드포인트를 각자 호출하는 기존 패턴과 동일).
  listFacilityOptions: () => api.get<InspectionFacilityOption[]>('/facilities'),
  // PATCH /api/defects/{id}/action — 하자 상세 모달의 "조치 완료 등록" 제출(DefectActionResultRequest,
  // contract.md §"조치 결과 등록" 확정, #726). 상태 전이(PATCH /status)와는 분리된 별도 엔드포인트로,
  // 백엔드가 내부에서 항상 IN_PROGRESS→RESOLVED로만 전이한다.
  submitAction: (id: number, body: DefectActionSubmitRequest) =>
    api.patch<Defect>(`/defects/${id}/action`, body).then((response) => ({
      ...response,
      data: normalizeDefect(response.data),
    })),
  // GET /api/defects/{id}/action-logs?phase= — 조치 등록 제출 이력 조회(#1193/HAJA-569 백엔드,
  // #1211/HAJA-574). 페이지네이션 없이 배열 전체를 반환하며, 백엔드가 createdAt 내림차순(최신 우선)
  // 으로 이미 정렬해 내려준다.
  getActionLogs: (id: number, phase: DefectActionLogPhase) =>
    api.get<DefectActionLogEntry[]>(`/defects/${id}/action-logs`, { params: { phase } }),
};

// 하자 목록 PDF 내보내기 — 화면 페이지와 무관하게 현재 필터에 해당하는 점검 전체를 모은다.
// 고정 페이지 상한을 두면 "필터 결과 전체" 계약을 조용히 위반하므로, 첫 응답의 totalElements를
// 완료 기준으로 삼는다. 중간에 빈 페이지나 중복 페이지만 오면 진행 불가 오류로 명시 실패한다.
export async function fetchAllFilteredInspections(
  filters: InspectionListFilters,
): Promise<InspectionListItem[]> {
  const filterOnly = { ...filters };
  delete filterOnly.page;
  delete filterOnly.size;

  const all: InspectionListItem[] = [];
  const seenIds = new Set<number>();
  let expectedTotal: number | null = null;

  for (let page = 0; expectedTotal == null || all.length < expectedTotal; page += 1) {
    const response = await defectApi.getInspections({
      ...filterOnly,
      page,
      size: INSPECTION_EXPORT_PAGE_SIZE,
    });
    const { content, totalElements } = response.data;

    if (!Number.isSafeInteger(totalElements) || totalElements < 0) {
      throw new Error('점검 내보내기 전체 건수가 올바르지 않습니다.');
    }
    expectedTotal ??= totalElements;

    const newItems = content.filter((inspection) => {
      if (seenIds.has(inspection.id)) return false;
      seenIds.add(inspection.id);
      return true;
    });
    if (newItems.length === 0 && all.length < expectedTotal) {
      throw new Error('점검 내보내기 데이터를 끝까지 불러오지 못했습니다.');
    }
    all.push(...newItems);
  }
  return all;
}

async function mapWithConcurrency<T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < items.length) {
      const currentIndex = nextIndex;
      nextIndex += 1;
      results[currentIndex] = await mapper(items[currentIndex]);
    }
  }

  const workerCount = Math.min(concurrency, items.length);
  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  return results;
}

export function filterDefectsForExport(
  defects: DefectExportItem[],
  filters: InspectionListFilters,
): DefectExportItem[] {
  return defects.filter((defect) => {
    const matchesType = !filters.defectType?.length || filters.defectType.includes(defect.type);
    const matchesGrade =
      !filters.defectGrade?.length ||
      (defect.grade != null && filters.defectGrade.includes(defect.grade));
    const matchesStatus =
      !filters.defectStatus?.length || filters.defectStatus.includes(defect.status);
    return matchesType && matchesGrade && matchesStatus;
  });
}

// 점검별 하자 API는 일괄 조회를 지원하지 않으므로 제한된 수의 워커로 호출한다.
// 반환 순서는 점검 목록 순서를 유지하고, 하자 조건 필터는 PDF의 실제 행에도 다시 적용한다.
export async function fetchFilteredDefectsForExport(
  filters: InspectionListFilters,
): Promise<DefectExportItem[]> {
  const inspections = await fetchAllFilteredInspections(filters);
  const defectsByInspection = await mapWithConcurrency(
    inspections,
    DEFECT_EXPORT_CONCURRENCY,
    (inspection) =>
      defectApi.getByInspection(inspection.id).then((response) =>
        response.data.map((defect) => ({
          ...defect,
          facilityName: inspection.facilityName,
        })),
      ),
  );
  return filterDefectsForExport(defectsByInspection.flat(), filters);
}
