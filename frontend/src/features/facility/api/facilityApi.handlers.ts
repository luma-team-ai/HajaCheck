import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockFacilities } from '../mocks/facility.mock';
import type { CreateFacilityRequest, Facility } from '../types';
import { computeNextInspectionDueAt } from '../utils/computeNextInspectionDueAt';

// 메모리 목 저장소 — POST로 생성한 시설물이 이후 GET 목록 조회에 즉시 반영되도록 모듈 스코프에서 유지
// (dashboardApi.handlers.ts처럼 고정 응답만으로는 등록 폼 E2E 확인이 불가능해 facility만 mutable로 구성)
let facilities: Facility[] = [...mockFacilities];
let nextId = facilities.reduce((max, facility) => Math.max(max, facility.id), 0) + 1;

// 테스트에서 setupServer(...facilityHandlers) + afterEach(() => server.resetHandlers())로 격리해도
// 이 모듈 스코프 상태(facilities/nextId)는 resetHandlers()로 초기화되지 않는다 — POST 등록 후
// GET 목록을 검증하는 테스트가 여러 it() 블록에 걸쳐 있을 때 상태가 새는 것을 막기 위해
// 명시적으로 호출 가능한 리셋 함수를 노출한다. FacilityListPage.test.tsx의
// afterEach(() => { server.resetHandlers(); resetFacilityMockStore(); })에서 사용한다.
export function resetFacilityMockStore(): void {
  facilities = [...mockFacilities];
  nextId = facilities.reduce((max, facility) => Math.max(max, facility.id), 0) + 1;
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
      // 실제 FacilityService는 클라이언트가 보낸 값을 그대로 저장(패스스루)할 뿐 자동계산하지 않는다.
      // FE는 항상 computeNextInspectionDueAt으로 산정해 보내지만, 이를 생략한 요청(예: 구버전 클라이언트,
      // 직접 호출하는 테스트)도 데모가 가능하도록 목에서만 동일 규칙으로 보정 계산한다.
      nextInspectionDueAt:
        reqBody.nextInspectionDueAt ?? computeNextInspectionDueAt(reqBody.inspectionCycleMonths),
      createdAt: now,
      updatedAt: now,
    };
    nextId += 1;
    facilities = [created, ...facilities];

    const body: ApiResponse<Facility> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),
];
