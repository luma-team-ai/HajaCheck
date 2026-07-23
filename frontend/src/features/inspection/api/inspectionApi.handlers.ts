import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type {
  FacilityDetail,
  FacilityOption,
  InspectionCreateRequest,
  InspectionCreateResponse,
} from '../types';
import type { DefectStatusUpdateRequest } from './inspectionApi';
import type { DefectDetailItem } from './inspectionApi.types';

// ponytail: /api/inspections/:id/result 목은 제거됨 — 실제 백엔드는
// /api/inspections/{id} + /api/inspections/{id}/defects 로 분리되어 있음.
// 이제 MSW를 끄고 실 API를 호출하거나, MSW를 켜도 두 개 엔드포인트를 따로 mock해야 함.

// 점검(회차) 생성 폼의 시설물 셀렉트 전용 목 — facility feature의 mockFacilities와는 별개
// (feature 간 직접 import 금지, 이름만 같은 화면 캡처 기준으로 맞춤).
const mockFacilityOptions: FacilityOption[] = [
  { id: 1, name: '강남 오피스타워 A동' },
  { id: 2, name: '판교 테크노밸리 B동' },
];

// 점검(회차) 생성 화면 상단 개요 패널용 시설물 상세 목 — facility feature의 mockFacilities id=1과
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
};

let nextInspectionId = 100;

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
      type: '균열',
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
];
