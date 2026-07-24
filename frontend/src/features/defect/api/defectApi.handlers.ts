import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import { mockDefectRevisions, mockDefects } from '../mocks/defect.mock';
import {
  mockDefectAssignees,
  mockInspectionFacilityOptions,
  mockInspections,
} from '../mocks/inspection.mock';
import type { NlSearchResult } from '../nlSearchTypes';
import type {
  Defect,
  DefectActionSubmitRequest,
  DefectAssignee,
  DefectRevision,
  DefectStatus,
  InspectionFacilityOption,
  InspectionGradeDistribution,
  InspectionListItem,
  InspectionStatus,
} from '../types';

const DEFAULT_SIZE = 20;
const DEFAULT_INSPECTION_PAGE_SIZE = 10;

// 점검별 하자 건수/등급분포는 mockInspections에 정적으로 박아두지 않고 mockDefects에서 매번 계산한다 —
// DefectDetailPage 통합 테스트가 mockDefects의 status/grade를 in-place로 바꾸는 것과 별개로, 목록
// 화면(HAJA-393/394)도 항상 최신 하자 데이터를 반영해야 하기 때문(단일 진실 소스).
function computeInspectionAggregates(inspectionId: number): {
  defectCount: number;
  gradeDistribution: InspectionGradeDistribution;
} {
  const defects = mockDefects.filter((defect) => defect.inspectionId === inspectionId);
  const gradeDistribution: InspectionGradeDistribution = { A: 0, B: 0, C: 0, D: 0, E: 0 };
  defects.forEach((defect) => {
    if (defect.grade) {
      gradeDistribution[defect.grade] += 1;
    }
  });
  return { defectCount: defects.length, gradeDistribution };
}

function toInspectionListItem(inspection: InspectionListItem): InspectionListItem {
  return { ...inspection, ...computeInspectionAggregates(inspection.id) };
}

// 백엔드 Defect#changeStatus 와 동일한 순서 — 신규→검수확정→조치대기→조치중→조치완료(역행/스킵 금지).
const NEXT_STATUS: Record<DefectStatus, DefectStatus | null> = {
  DETECTED: 'CONFIRMED',
  CONFIRMED: 'ACTION_PENDING',
  ACTION_PENDING: 'IN_PROGRESS',
  IN_PROGRESS: 'RESOLVED',
  RESOLVED: null,
};

export const defectHandlers = [
  http.get('/api/defects', ({ request }) => {
    const url = new URL(request.url);
    const type = url.searchParams.get('type');
    const grade = url.searchParams.get('grade');
    const status = url.searchParams.get('status');
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? String(DEFAULT_SIZE));

    const filtered = mockDefects.filter(
      (defect) =>
        (!type || defect.type === type) &&
        (!grade || defect.grade === grade) &&
        (!status || defect.status === status),
    );

    const content = filtered.slice(page * size, page * size + size);
    const body: ApiResponse<PageResponse<Defect>> = {
      success: true,
      data: { content, page, totalElements: filtered.length },
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/defects/:id', ({ params }) => {
    const id = Number(params.id);
    const found = mockDefects.find((defect) => defect.id === id);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'DEFECT_NOT_FOUND', message: '하자를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const body: ApiResponse<Defect> = { success: true, data: found };
    return HttpResponse.json(body);
  }),

  http.patch('/api/defects/:id/status', async ({ params, request }) => {
    const id = Number(params.id);
    const found = mockDefects.find((defect) => defect.id === id);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'DEFECT_NOT_FOUND', message: '하자를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const reqBody = (await request.json()) as { status: DefectStatus; reason?: string } & Partial<
      Omit<DefectActionSubmitRequest, 'status'>
    >;
    const { status, reason } = reqBody;

    // RESOLVED에서의 이탈은 reason 유무와 무관하게 409(조치 보드 드래그 전이, HAJA-349/#630 handoff 범위 §API).
    if (found.status === 'RESOLVED' && status !== 'RESOLVED') {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INVALID_STATE_TRANSITION', message: '현재 상태에서는 처리할 수 없는 요청입니다.' },
      };
      return HttpResponse.json(failure, { status: 409 });
    }

    const expectedNext = NEXT_STATUS[found.status];
    const isForward = status === expectedNext;

    // 역행·건너뛰기 전이는 reason이 있어야 허용된다(정방향 1단계만 reason 없이 통과). "조치 완료 등록"
    // 모달 제출(actionContent 포함)은 조치 보드 드래그 전이와 달리 사유 대신 조치내용/조치일/담당자를
    // 함께 보내므로 reason 요구 대상에서 제외한다(HAJA-394/#726, contract.md §"조치 결과 등록").
    const isActionRegistration = reqBody.actionContent != null;
    if (!isForward && !isActionRegistration && (!reason || reason.trim().length === 0)) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INVALID_INPUT', message: '상태를 되돌리거나 건너뛰려면 사유가 필요합니다.' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    found.status = status;

    // "조치 완료 등록" 제출(HAJA-394/#726) — PATCH /api/defects/{id}/status 확장 가정(BE 판단 대기,
    // contract.md §"조치 결과 등록"). afterMediaId는 POST /api/inspections/{id}/media 응답에서 받은
    // 사진 id를 그대로 회신 표시용 URL로 치환한다(실 저장 컬럼은 Flyway V5 대기).
    if (isActionRegistration && reqBody.actionContent && reqBody.actionDate && reqBody.assigneeId != null) {
      const assignee = mockDefectAssignees.find((candidate) => candidate.id === reqBody.assigneeId);
      found.actionResult = {
        actionContent: reqBody.actionContent,
        actionDate: reqBody.actionDate,
        assigneeId: reqBody.assigneeId,
        assigneeName: assignee?.name ?? '담당자 미상',
        afterPhotoUrl: reqBody.afterMediaId != null ? `/api/media/${reqBody.afterMediaId}/thumbnail` : null,
      };
    }

    const body: ApiResponse<Defect> = { success: true, data: found };
    return HttpResponse.json(body);
  }),

  http.get('/api/defects/:id/revisions', ({ params, request }) => {
    const id = Number(params.id);
    const found = mockDefects.find((defect) => defect.id === id);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'DEFECT_NOT_FOUND', message: '하자를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? String(DEFAULT_SIZE));
    const revisions = mockDefectRevisions[id] ?? [];
    const content = revisions.slice(page * size, page * size + size);
    const body: ApiResponse<PageResponse<DefectRevision>> = {
      success: true,
      data: { content, page, totalElements: revisions.length },
    };
    return HttpResponse.json(body);
  }),

  // "D등급 이상" 질의는 정상 필터 변환, 그 외는 되묻는 질문 응답 — 테스트 fixture 단순화(HAJA-120).
  http.post('/api/defects/nl-search', async ({ request }) => {
    const { query } = (await request.json()) as { query: string };

    if (query.includes('D등급 이상')) {
      const body: ApiResponse<NlSearchResult> = {
        success: true,
        data: {
          filters: { type: [], grade: ['D', 'E'], status: ['ACTION_PENDING'], confidenceMin: null },
          unsupported_terms: [],
          clarifying_question: null,
          interpretation_confidence: 0.92,
        },
      };
      return HttpResponse.json(body);
    }

    const body: ApiResponse<NlSearchResult> = {
      success: true,
      data: {
        filters: { type: [], grade: [], status: [], confidenceMin: null },
        unsupported_terms: [],
        clarifying_question: '어떤 유형·등급·상태의 하자를 찾으시나요?',
        interpretation_confidence: 0.2,
      },
    };
    return HttpResponse.json(body);
  }),

  // --- 하자 목록·상세 개편 (HAJA-393/394, #725/#726) ---------------------------------------

  // GET /api/inspections — 점검 단위 목록(신규, BE 미구현). 상태/시설물 필터 + 페이지네이션.
  http.get('/api/inspections', ({ request }) => {
    const url = new URL(request.url);
    const status = url.searchParams.get('status') as InspectionStatus | null;
    const facilityIdParam = url.searchParams.get('facilityId');
    const facilityId = facilityIdParam ? Number(facilityIdParam) : null;
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? String(DEFAULT_INSPECTION_PAGE_SIZE));

    const filtered = mockInspections
      .filter(
        (inspection) =>
          (!status || inspection.status === status) &&
          (facilityId == null || inspection.facilityId === facilityId),
      )
      .map(toInspectionListItem);

    const content = filtered.slice(page * size, page * size + size);
    const body: ApiResponse<PageResponse<InspectionListItem>> = {
      success: true,
      data: { content, page, totalElements: filtered.length },
    };
    return HttpResponse.json(body);
  }),

  // GET /api/inspections/{id}/defects — 점검별 하자 카드 목록(카드형 상세, contract.md §②).
  // inspection feature의 동일 엔드포인트가 별도로 이 경로를 mock하지 않아(inspectionApi.handlers.ts
  // 참고 — 실제로 미등록 상태였음), defect feature 소유로 여기에 등록한다.
  http.get('/api/inspections/:id/defects', ({ params }) => {
    const inspectionId = Number(params.id);
    const found = mockInspections.find((inspection) => inspection.id === inspectionId);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INSPECTION_NOT_FOUND', message: '점검 회차를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const defects = mockDefects.filter((defect) => defect.inspectionId === inspectionId);
    const body: ApiResponse<Defect[]> = { success: true, data: defects };
    return HttpResponse.json(body);
  }),

  // GET /api/facilities — 점검 목록 필터의 시설물 select 옵션(defect feature 자체 목, feature 간
  // 직접 import 금지 컨벤션에 따라 inspection/facility feature의 동일 핸들러와 별개로 등록).
  http.get('/api/facilities', () => {
    const body: ApiResponse<InspectionFacilityOption[]> = {
      success: true,
      data: mockInspectionFacilityOptions,
    };
    return HttpResponse.json(body);
  }),

  // GET /api/facilities/assignable-users — 하자 상세 모달 "담당자" select 옵션(#690 재사용 추정,
  // 실 API 계약 없음 — facility feature의 동일 목과 별개로 defect feature 자체 목).
  http.get('/api/facilities/assignable-users', () => {
    const body: ApiResponse<DefectAssignee[]> = { success: true, data: mockDefectAssignees };
    return HttpResponse.json(body);
  }),

  // POST /api/inspections/:id/media — "조치 후 사진 업로드"(defect feature 자체 목, inspection
  // feature의 mediaApi.handlers.ts와 별개로 등록해 defect 테스트를 독립적으로 유지한다).
  http.post('/api/inspections/:id/media', async ({ params, request }) => {
    const inspectionId = Number(params.id);
    const found = mockInspections.find((inspection) => inspection.id === inspectionId);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'INSPECTION_NOT_FOUND', message: '점검 회차를 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const formData = await request.formData();
    const files = formData.getAll('files').filter((entry): entry is File => entry instanceof File);

    if (files.length === 0) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FILE_REQUIRED', message: '파일이 필요합니다.' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const created = files.map((file, index) => ({
      id: Date.now() + index,
      thumbnailUrl:
        'data:image/svg+xml;utf8,' +
        encodeURIComponent(
          '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300">' +
            '<rect width="100%" height="100%" fill="#d9d9d9"/>' +
            `<text x="50%" y="50%" font-size="18" fill="#888" text-anchor="middle">${file.name}</text>` +
            '</svg>',
        ),
    }));

    const body: ApiResponse<typeof created> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),
];
