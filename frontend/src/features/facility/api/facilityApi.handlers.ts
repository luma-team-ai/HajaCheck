import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockFacilities } from '../mocks/facility.mock';
import type {
  CreateFacilityRequest,
  Facility,
  InspectionNotificationSettings,
  SetFacilityScheduleRequest,
  SetFacilityScheduleResponse,
  SetInspectionNotificationSettingsRequest,
} from '../types';
import { computeNextInspectionDueAt } from '../utils/computeNextInspectionDueAt';
import { computeDemoNextInspectionDueAt } from '../utils/inspectionCycleDemo';


// 메모리 목 저장소 — POST로 생성한 시설물이 이후 GET 목록 조회에 즉시 반영되도록 모듈 스코프에서 유지
// (dashboardApi.handlers.ts처럼 고정 응답만으로는 등록 폼 E2E 확인이 불가능해 facility만 mutable로 구성)
let facilities: Facility[] = [...mockFacilities];
let nextId = facilities.reduce((max, facility) => Math.max(max, facility.id), 0) + 1;

// 점검 알림 설정 목 저장소(#540 ③) — facilityId별 설정. 저장한 적 없는 시설물은 서버 컬럼 기본값과
// 동일한 기본값으로 응답한다. warnOnOverdueEnabled는 true가 기본이다(HAJA-498/V21 — false로 시작했다가
// 연체 시설물 알림 미발행 회귀가 발견돼 Polalise 승인(옵션1)으로 되돌림).
const DEFAULT_NOTIFICATION_SETTINGS: InspectionNotificationSettings = {
  notifyBeforeEnabled: true,
  notifyBeforeDays: 7,
  warnOnOverdueEnabled: true,
};
let notificationSettingsByFacilityId = new Map<number, InspectionNotificationSettings>();

// 테스트에서 setupServer(...facilityHandlers) + afterEach(() => server.resetHandlers())로 격리해도
// 이 모듈 스코프 상태(facilities/nextId)는 resetHandlers()로 초기화되지 않는다 — POST 등록 후
// GET 목록을 검증하는 테스트가 여러 it() 블록에 걸쳐 있을 때 상태가 새는 것을 막기 위해
// 명시적으로 호출 가능한 리셋 함수를 노출한다. FacilityListPage.test.tsx의
// afterEach(() => { server.resetHandlers(); resetFacilityMockStore(); })에서 사용한다.
export function resetFacilityMockStore(): void {
  facilities = [...mockFacilities];
  nextId = facilities.reduce((max, facility) => Math.max(max, facility.id), 0) + 1;
  notificationSettingsByFacilityId = new Map<number, InspectionNotificationSettings>();
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
      // #628(HAJA-347) 등록 필드 확장 — 전부 선택 입력
      initialGrade: reqBody.initialGrade ?? null,
      assigneeUserId: reqBody.assigneeUserId ?? null,
      memo: reqBody.memo ?? null,
      latestDefectId: null,
      // 대표 사진은 등록 후 별도 업로드 API로 붙는다(#652) — 등록 직후엔 항상 null.
      thumbnailUrl: null,
      // 신규 등록 시설물은 아직 점검 이력이 없다.
      lastInspectedAt: null,
    };
    nextId += 1;
    facilities = [created, ...facilities];

    const body: ApiResponse<Facility> = { success: true, data: created };
    return HttpResponse.json(body, { status: 201 });
  }),

  // 시설물 수정(PUT — 전체 교체). 좌표 소급 재-geocoding(#618)이 이 핸들러로 latitude/longitude를 갱신한다.
  http.put('/api/facilities/:id', async ({ params, request }) => {
    const id = Number(params.id);
    const reqBody = (await request.json()) as CreateFacilityRequest;
    const target = facilities.find((facility) => facility.id === id);

    if (!target) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_NOT_FOUND', message: '시설물을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    if (!reqBody.name?.trim() || !reqBody.type?.trim()) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_VALIDATION_ERROR', message: '시설물명과 유형은 필수입니다.' },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    const updated: Facility = {
      ...target,
      name: reqBody.name,
      type: reqBody.type,
      address: reqBody.address ?? null,
      latitude: reqBody.latitude ?? null,
      longitude: reqBody.longitude ?? null,
      builtYear: reqBody.builtYear ?? null,
      scale: reqBody.scale ?? null,
      inspectionCycleMonths: reqBody.inspectionCycleMonths ?? null,
      nextInspectionDueAt: reqBody.nextInspectionDueAt ?? null,
      // PUT은 전체 교체(FacilityUpdateRequest와 1:1) — POST와 동일하게 값을 그대로 반영한다.
      initialGrade: reqBody.initialGrade ?? null,
      assigneeUserId: reqBody.assigneeUserId ?? null,
      memo: reqBody.memo ?? null,
      latestDefectId: null,
      updatedAt: new Date().toISOString(),
    };
    facilities = facilities.map((facility) => (facility.id === id ? updated : facility));

    const body: ApiResponse<Facility> = { success: true, data: updated };
    return HttpResponse.json(body);
  }),

  // 점검 주기 설정(dev-04-03, FR-019) — 요청 개월 수 기준 nextInspectionDueAt을 산정해 저장.
  // computeNextInspectionDueAt과 동일 규칙을 재사용해 등록 시나리오와 계산 로직이 어긋나지 않게 한다.
  http.post('/api/facilities/:id/schedule', async ({ params, request }) => {
    const id = Number(params.id);
    const reqBody = (await request.json()) as SetFacilityScheduleRequest;
    const target = facilities.find((facility) => facility.id === id);

    if (!target) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_NOT_FOUND', message: '시설물을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    if (!reqBody.inspectionCycleMonths || reqBody.inspectionCycleMonths <= 0) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: {
          code: 'SCHEDULE_VALIDATION_ERROR',
          message: '점검 주기는 1개월 이상이어야 합니다.',
        },
      };
      return HttpResponse.json(failure, { status: 400 });
    }

    // 점검 주기 설정 화면은 데모 기준일(INSPECTION_CYCLE_DEMO_TODAY)로 상태 뱃지를 파생하므로,
    // 저장 다음점검일도 같은 기준일로 산정해 저장 후 좌/우 뱃지가 어긋나지 않게 한다.
    // (등록 플로우 POST /facilities는 실제 오늘 기준 computeNextInspectionDueAt을 그대로 사용.)
    const nextInspectionDueAt = computeDemoNextInspectionDueAt(reqBody.inspectionCycleMonths);
    facilities = facilities.map((facility) =>
      facility.id === id
        ? {
            ...facility,
            inspectionCycleMonths: reqBody.inspectionCycleMonths,
            nextInspectionDueAt,
            updatedAt: new Date().toISOString(),
          }
        : facility,
    );

    const responseBody: ApiResponse<SetFacilityScheduleResponse> = {
      success: true,
      data: { nextInspectionDueAt },
    };
    return HttpResponse.json(responseBody);
  }),

  // 점검 알림 설정 조회(#540 ③) — 저장한 적 없으면 기본값(사전알림 사용/7일전/경과알림 사용, HAJA-498/V21) 반환.
  http.get('/api/facilities/:id/notification-settings', ({ params }) => {
    const id = Number(params.id);
    const target = facilities.find((facility) => facility.id === id);

    if (!target) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_NOT_FOUND', message: '시설물을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const data = notificationSettingsByFacilityId.get(id) ?? DEFAULT_NOTIFICATION_SETTINGS;
    const body: ApiResponse<InspectionNotificationSettings> = { success: true, data };
    return HttpResponse.json(body);
  }),

  // 점검 알림 설정 저장(upsert, #540 ③).
  http.put('/api/facilities/:id/notification-settings', async ({ params, request }) => {
    const id = Number(params.id);
    const target = facilities.find((facility) => facility.id === id);

    if (!target) {
      const failure: ApiResponse<null> = {
        success: false,
        data: null,
        error: { code: 'FACILITY_NOT_FOUND', message: '시설물을 찾을 수 없습니다.' },
      };
      return HttpResponse.json(failure, { status: 404 });
    }

    const reqBody = (await request.json()) as SetInspectionNotificationSettingsRequest;
    notificationSettingsByFacilityId.set(id, reqBody);

    const body: ApiResponse<InspectionNotificationSettings> = { success: true, data: reqBody };
    return HttpResponse.json(body);
  }),
];
