import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import type {
  FacilityDetail,
  FacilityOption,
  InspectionCreateRequest,
  InspectionCreateResponse,
} from '../types';
import type { DefectStatusUpdateRequest } from './inspectionApi';
import type {
  DefectDetailItem,
  DefectCreateRequest,
  FacilityInspectionSummary,
} from './inspectionApi.types';

// ponytail: /api/inspections/:id/result 목은 제거됨 — 실제 백엔드는
// /api/inspections/{id} + /api/inspections/{id}/defects 로 분리되어 있음.
// 이제 MSW를 끄고 실 API를 호출하거나, MSW를 켜도 두 개 엔드포인트를 따로 mock해야 함.

// 점검(회차) 생성 폼의 시설물 셀렉트 전용 목 — facility feature의 mockFacilities와는 별개
// (feature 간 직접 import 금지, 이름만 같은 화면 캡처 기준으로 맞춤).
// id=3(한강대교 북단)은 features/defect/mocks/inspection.mock.ts의 mockInspections(id=202,
// HAJA-393/394 · #725/#726)가 참조하는 시설물 — 이 핸들러가 defectHandlers보다 먼저 등록돼
// GET /api/facilities를 가로채므로, 여기 목록에도 추가해야 InspectionFilterBar 시설물 select에
// 그 옵션이 노출된다(PR머신 P2 지적).
const mockFacilityOptions: FacilityOption[] = [
  { id: 1, name: '강남 오피스타워 A동' },
  { id: 2, name: '판교 테크노밸리 B동' },
  { id: 3, name: '한강대교 북단' },
];

// 분석 결과 뷰어(useInspectionResultReal)용 시설물 상세 목 — facility feature의 mockFacilities id=1~3과
// 동일 데모 시설물(값만 로컬 복제, cross-feature import 금지).
const mockFacilityDetails: Record<number, FacilityDetail> = {
  1: {
    id: 1,
    name: '강남 오피스타워 A동',
    type: '건물',
    address: '서울 강남구 테헤란로 123',
    builtYear: 2008,
    scale: '지상 20층, 지하 5층',
    nextInspectionDueAt: '2026-09-15',
  },
  2: {
    id: 2,
    name: '판교 테크노밸리 B동',
    type: '건물',
    address: '경기 성남시 분당구 판교역로 235',
    builtYear: 2012,
    scale: '지상 15층, 지하 3층',
    nextInspectionDueAt: '2027-01-05',
  },
  3: {
    id: 3,
    name: '한강대교 북단',
    type: '교량',
    address: '서울 용산구 한강대로 인근',
    builtYear: 1985,
    scale: '연장 1,005m, 왕복 8차로',
    nextInspectionDueAt: '2026-08-01',
  },
};

let nextInspectionId = 100;
let nextDefectId = 1000;

export const inspectionHandlers = [
  http.get('/api/facilities', () => {
    const body: ApiResponse<FacilityOption[]> = { success: true, data: mockFacilityOptions };
    return HttpResponse.json(body);
  }),

  http.get('/api/facilities/:id', ({ params }) => {
    const id = Number(params.id);
    const found = mockFacilityDetails[id];

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_NOT_FOUND', message: '시설물을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const body: ApiResponse<FacilityDetail> = { success: true, data: found };
    return HttpResponse.json(body);
  }),

  http.patch('/api/defects/:id/status', async ({ params, request }) => {
    const defectId = Number(params.id);
    const reqBody = (await request.json()) as DefectStatusUpdateRequest;

    // Mock handler: allow all valid status transitions except for testing edge cases
    // In tests, specific scenarios (e.g., already-reviewed defect) can override this handler
    const data: DefectDetailItem = {
      id: defectId,
      status: reqBody.status,
      // Return a minimal defect object to satisfy the type contract
      inspectionId: 1,
      type: 'CRACK',
      grade: 'A',
      confidence: 0.95,
      isReviewed: true,
      bboxX: 100,
      bboxY: 100,
      bboxW: 50,
      bboxH: 50,
      crackLengthMm: 150,
      createdAt: new Date().toISOString(),
    };
    const result: ApiResponse<DefectDetailItem> = {
      success: true,
      data,
    };
    return HttpResponse.json(result);
  }),

  // 점검 생성 폼의 "같은 시설물에 이미 진행 중인 회차가 있는지" 확인용 — 기본값은 항상 빈 목록
  // (진행 중인 회차 없음)이라 기존 제출 플로우 테스트에 영향 없다. 중복 경고 시나리오는 개별
  // 테스트에서 server.use로 override한다.
  //
  // 이 핸들러는 inspectionApi.listByFacility(facilityId만 전송, page/size 없음) 전용이다.
  // 하자 목록(defect feature)의 실 점검 목록 조회(useInspections)는 DefectListPage 기본값부터
  // page/size를 항상 함께 보내는데, mocks/handlers.ts 등록 순서상 inspectionHandlers가
  // defectHandlers보다 먼저라 이 핸들러가 먼저 매치돼 필터(자연어 검색 결과 포함)를 전부 무시한 채
  // 항상 빈 목록을 반환해버렸다(하자 목록 자연어 검색 필터링 무동작 버그). page 파라미터가 있으면
  // 이 핸들러가 응답을 만들지 않고 undefined를 반환해 다음으로 매칭되는 defectApi.handlers.ts의
  // 실제 필터 구현 핸들러로 폴스루한다(msw v2 — resolver가 응답을 반환하지 않으면 다음 매칭
  // 핸들러를 시도).
  http.get('/api/inspections', ({ request }) => {
    const url = new URL(request.url);
    if (url.searchParams.has('page')) {
      return;
    }
    const body: ApiResponse<PageResponse<FacilityInspectionSummary>> = {
      success: true,
      data: { content: [], page: 0, totalElements: 0 },
    };
    return HttpResponse.json(body);
  }),

  http.post('/api/inspections', async ({ request }) => {
    const reqBody = (await request.json()) as InspectionCreateRequest;
    const facility = mockFacilityOptions.find((option) => option.id === reqBody.facilityId);

    if (!facility) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_NOT_FOUND', message: '시설물을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const created: InspectionCreateResponse = {
      id: nextInspectionId,
      facilityId: reqBody.facilityId,
      createdBy: 1,
      assignedInspectorId: reqBody.assignedInspectorId,
      roundNo: 1,
      inspectionDate: reqBody.inspectionDate,
      status: 'SCHEDULED',
      createdAt: new Date().toISOString(),
    };
    nextInspectionId += 1;

    const body: ApiResponse<InspectionCreateResponse> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),

  http.post('/api/inspections/:id/defects', async ({ params, request }) => {
    const inspectionId = Number(params.id);
    const reqBody = (await request.json()) as DefectCreateRequest;

    // 실 백엔드와 동일하게 영문 코드 그대로 반환 — 한글 라벨 번역은 프론트 훅에서 한다(#881).
    const newDefect: DefectDetailItem = {
      id: nextDefectId,
      inspectionId,
      type: reqBody.type,
      grade: reqBody.grade,
      confidence: 1.0,
      status: 'DETECTED',
      isReviewed: false,
      bboxX: null,
      bboxY: null,
      bboxW: null,
      bboxH: null,
      createdAt: new Date().toISOString(),
    };
    nextDefectId += 1;

    const body: ApiResponse<DefectDetailItem> = { success: true, data: newDefect };
    return HttpResponse.json(body, { status: 201 });
  }),

  // AI 분석 시작(dev-05-04) — InspectionCreatePage 제출 흐름이 업로드 직후 호출한다. 실제 진행률은
  // useAnalysisStatus 전용 테스트에서 다루므로 여기서는 202만 흉내낸다.
  http.post('/api/inspections/:id/analyze', () => new HttpResponse(null, { status: 202 })),

  // 점검 요약 진입 시 회차 검수 확정(ANALYZED→REVIEWED) — ResultViewerPage "점검 요약" 버튼.
  http.post('/api/inspections/:id/confirm-review', () => HttpResponse.json({ success: true, data: null })),

  // 점검 상세 조회 — useInspectionResultReal에서 사용. mockInspections 데이터를 기반으로 응답한다.
  http.get('/api/inspections/:id', ({ params }) => {
    const id = Number(params.id);
    const mockInspectionsData = [
      { id: 101, facilityId: 1, roundNo: 3, inspectionDate: '2026-07-01', type: 'REGULAR', status: 'REVIEWED' },
      { id: 202, facilityId: 3, roundNo: 1, inspectionDate: '2026-07-03', type: 'DETAILED', status: 'ANALYZED' },
      { id: 301, facilityId: 2, roundNo: 2, inspectionDate: '2026-06-20', type: 'EMERGENCY', status: 'REPORTED' },
    ];
    const found = mockInspectionsData.find((i) => i.id === id);
    if (!found) {
      return HttpResponse.json({ success: false, data: null, error: { code: 'INSPECTION_NOT_FOUND', message: '점검을 찾을 수 없습니다.' } }, { status: 404 });
    }
    return HttpResponse.json({ success: true, data: found });
  }),
];
