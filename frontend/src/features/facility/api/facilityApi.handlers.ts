import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockFacilities } from '../mocks/facility.mock';
import type { CreateFacilityRequest, Facility } from '../types';

// 메모리 목 저장소 — POST로 생성한 시설물이 이후 GET 목록 조회에 즉시 반영되도록 모듈 스코프에서 유지
// (dashboardApi.handlers.ts처럼 고정 응답만으로는 등록 폼 E2E 확인이 불가능해 facility만 mutable로 구성)
let facilities: Facility[] = [...mockFacilities];
let nextId = facilities.reduce((max, facility) => Math.max(max, facility.id), 0) + 1;

// 점검주기(개월) 기준으로 다음 점검일을 오늘부터 산정 — 실제 백엔드 산정 로직의 목(mock) 근사치
function computeNextInspectionDueAt(inspectionCycleMonths?: number | null): string | null {
  if (!inspectionCycleMonths || inspectionCycleMonths <= 0) {
    return null;
  }
  const due = new Date();
  due.setMonth(due.getMonth() + inspectionCycleMonths);
  return due.toISOString().slice(0, 10);
}

export const facilityHandlers = [
  http.get('/api/facilities', () => {
    const body: ApiResponse<Facility[]> = { success: true, data: facilities };
    return HttpResponse.json(body);
  }),

  http.get('/api/facilities/:id', ({ params }) => {
    const id = Number(params.id);
    const found = facilities.find((facility) => facility.id === id);

    if (!found) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_NOT_FOUND', message: '시설물을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const body: ApiResponse<Facility> = { success: true, data: found };
    return HttpResponse.json(body);
  }),

  http.post('/api/facilities', async ({ request }) => {
    const reqBody = (await request.json()) as CreateFacilityRequest;

    // 최소 서버측 검증 목 — 클라이언트 검증(FacilityFormModal)과 별개로 계약 위반 요청을 재현
    if (!reqBody.name?.trim() || !reqBody.type?.trim()) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_VALIDATION_ERROR', message: '시설물명과 유형은 필수입니다.' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const now = new Date().toISOString();
    const created: Facility = {
      id: nextId,
      ownerId: 1,
      name: reqBody.name,
      type: reqBody.type,
      address: reqBody.address ?? null,
      latitude: reqBody.latitude ?? null,
      longitude: reqBody.longitude ?? null,
      builtYear: reqBody.builtYear ?? null,
      scale: reqBody.scale ?? null,
      inspectionCycleMonths: reqBody.inspectionCycleMonths ?? null,
      nextInspectionDueAt: computeNextInspectionDueAt(reqBody.inspectionCycleMonths),
      createdAt: now,
      updatedAt: now,
    };
    nextId += 1;
    facilities = [created, ...facilities];

    const body: ApiResponse<Facility> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),
];
