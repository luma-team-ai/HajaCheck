import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockPlanQuotaUsers } from '../mocks/planQuotaUsers.mock';
import type {
  AdminCurrentPlanResponse,
  AdminPlanCatalogResponse,
  AdminScheduledPlanChange,
  AdminScheduledPlanChangeRequestPayload,
  PlanChangePreviewResponse,
  PlanChangePreviewSuspendTarget,
  PlanChangeRequestPayload,
} from '../planQuota.types';
import type { AdminUserPlan } from '../types';

// GET /api/admin/plans MSW 목 — docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql의
// 실제 시드값과 동일하게 맞춘다(PRD_hajaCheck.md §2.4 요금제 표 기준, HAJA-308). FREE는 max_seats=1
// (계정 소유자 본인 1석, "추가 초대 좌석 없음"을 의미), ENTERPRISE는 max_seats=null(무제한)이다.
export const mockAdminPlanCatalog: AdminPlanCatalogResponse = {
  plans: [
    {
      id: 1,
      name: 'FREE',
      maxFacilities: 1,
      maxMonthlyAnalyses: 50,
      maxSeats: 1,
      hasPdfWatermark: true,
      hasCounselorAccess: false,
      hasAiAddon: false,
      priceMonthly: 0,
    },
    {
      id: 2,
      name: 'STANDARD',
      maxFacilities: 10,
      maxMonthlyAnalyses: 1000,
      maxSeats: 3,
      hasPdfWatermark: false,
      hasCounselorAccess: true,
      hasAiAddon: true,
      priceMonthly: 29000,
    },
    {
      id: 3,
      name: 'ENTERPRISE',
      maxFacilities: null,
      maxMonthlyAnalyses: null,
      maxSeats: null,
      hasPdfWatermark: false,
      hasCounselorAccess: true,
      hasAiAddon: true,
      priceMonthly: 59000,
    },
  ],
};

// GET /api/admin/plan MSW 목 — 좌석 여유가 있는 STANDARD 기본값(1/3석)으로 둔다. 좌석이 가득 찬
// 케이스를 확인하는 테스트는 server.use()로 이 핸들러를 개별 덮어써 재현한다(#872 후속).
// currentPeriodEnd는 하향 예약(#1105 / HAJA-526, #1191) 적용 예정일 표시에 쓴다 — null(무기한)
// 케이스를 확인하는 테스트는 server.use()로 개별 덮어쓴다.
export const mockAdminCurrentPlan: AdminCurrentPlanResponse = {
  subscriptionId: 1,
  plan: mockAdminPlanCatalog.plans[1],
  status: 'ACTIVE',
  startedAt: '2026-01-01T00:00:00Z',
  currentPeriodEnd: '2026-08-21T09:00:00Z',
  scheduledChange: null,
  usage: {
    analyzedImageCount: 0,
    analysisRequestCount: 0,
    facilityCount: 0,
    seatCount: 1,
    period: '2026-07-01',
  },
};

// POST/DELETE /api/admin/plan/scheduled-change(#1105 / HAJA-526, #1191)의 대기 중 예약 — 모듈 스코프
// 상태로 들고 있어야 POST 이후 GET /api/admin/plan 재조회(useScheduleDowngrade invalidate)에도
// 갱신된 값이 보인다(mypageApi.handlers.ts의 mockMyPlanState와 동일 패턴). resetHandlers()로는
// 초기화되지 않으므로, 테스트가 시나리오를 넘나들며 격리가 필요하면 resetAdminPlanScheduleMockStore를
// afterEach에서 함께 호출한다.
let mockScheduledChangeState: AdminScheduledPlanChange | null = null;
let mockScheduledChangeIdSeq = 1;

export function resetAdminPlanScheduleMockStore(): void {
  mockScheduledChangeState = null;
  mockScheduledChangeIdSeq = 1;
}

// ── 플랜 변경 미리보기·실행 목(#890 Phase 1/2) ──
// planQuotaUsers.mock.ts 의 활성 멤버(plan !== null, 7명 — 1번 김민준을 "회사 owner"로 가정)로 좌석
// 초과를 재현한다. 시설물 보유량은 이 목에 없는 값이라 임의 상수로 둔다(화면 렌더 확인 목적).
const MOCK_ACTIVE_MEMBER_IDS = mockPlanQuotaUsers.filter((user) => user.plan !== null).map((user) => user.id);
const MOCK_OWNER_ID = MOCK_ACTIVE_MEMBER_IDS[0];
const MOCK_OWNED_FACILITY_COUNT = 12;

function findCatalogItem(planName: string | null) {
  return mockAdminPlanCatalog.plans.find((item) => item.name === planName);
}

// 백엔드 PLAN_KEEP_USER_INVALID/PLAN_SEAT_QUOTA_EXCEEDED 거절을 목에서도 실제로 재현하기 위한
// 전용 에러 — 이전에는 "동일한 규칙 재현"이라는 주석만 있고 실제 거절 로직이 없어 어떤 keepUserIds를
// 보내도 조용히 통과했다(#930 재검토 F-4/F-5). 두 핸들러(GET/PATCH)가 이 에러를 잡아 403으로 변환한다.
class PlanKeepUserRejection extends Error {
  constructor(
    public code: 'PLAN_KEEP_USER_INVALID' | 'PLAN_SEAT_QUOTA_EXCEEDED',
    message: string,
  ) {
    super(message);
  }
}

// 백엔드 PlanDowngradeService#preview/resolveSeatsToSuspend 와 동일한 규칙을 목에서도 재현한다:
// ① keepUserIds가 지정되면 활성 멤버 스코프인지 항상 검증(좌석 여유 여부와 무관 — F-10과 동일 원칙),
// ② owner는 항상 유지, ③ keepUserIds 지정 시 좌석 초과분을 자동으로 줄이지 않고 그대로 거절한다.
function resolveSeatsToSuspend(maxSeats: number | null, keepUserIds: number[]): PlanChangePreviewSuspendTarget[] {
  const hasSelection = keepUserIds.length > 0;
  if (hasSelection) {
    for (const id of keepUserIds) {
      if (!MOCK_ACTIVE_MEMBER_IDS.includes(id)) {
        throw new PlanKeepUserRejection(
          'PLAN_KEEP_USER_INVALID',
          '선택한 구성원 중 일부가 유효하지 않습니다.',
        );
      }
    }
  }

  if (maxSeats === null || MOCK_ACTIVE_MEMBER_IDS.length <= maxSeats) {
    return [];
  }

  const keep = new Set<number>([MOCK_OWNER_ID, ...keepUserIds]);
  if (!hasSelection) {
    for (const id of MOCK_ACTIVE_MEMBER_IDS) {
      if (keep.size >= maxSeats) {
        break;
      }
      keep.add(id);
    }
  } else if (keep.size > maxSeats) {
    throw new PlanKeepUserRejection(
      'PLAN_SEAT_QUOTA_EXCEEDED',
      '선택한 유지 인원이 좌석 한도를 초과합니다.',
    );
  }

  return MOCK_ACTIVE_MEMBER_IDS.filter((id) => !keep.has(id)).map((id) => {
    const user = mockPlanQuotaUsers.find((candidate) => candidate.id === id);
    return { userId: id, name: user?.name ?? `구성원${id}`, email: user?.email ?? '' };
  });
}

function rejectionResponse(e: PlanKeepUserRejection) {
  return HttpResponse.json(
    { success: false, data: null, error: { code: e.code, message: e.message } },
    { status: 403 },
  );
}

function computeFacilityOverflow(maxFacilities: number | null): number {
  return maxFacilities === null ? 0 : Math.max(0, MOCK_OWNED_FACILITY_COUNT - maxFacilities);
}

export const adminPlanHandlers = [
  http.get('/api/admin/plans', () => {
    const body: ApiResponse<AdminPlanCatalogResponse> = { success: true, data: mockAdminPlanCatalog };
    return HttpResponse.json(body);
  }),

  http.get('/api/admin/plan', () => {
    const body: ApiResponse<AdminCurrentPlanResponse> = {
      success: true,
      data: { ...mockAdminCurrentPlan, scheduledChange: mockScheduledChangeState },
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/admin/plan/change-preview', ({ request }) => {
    const url = new URL(request.url);
    const planName = url.searchParams.get('planName');
    const keepUserIds = url.searchParams.getAll('keepUserIds').map(Number);
    const target = findCatalogItem(planName);
    if (!target) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: 'PLAN_DATA_INVALID', message: '요금제 데이터에 오류가 있습니다.' },
        },
        { status: 500 },
      );
    }

    let seatsToSuspend: PlanChangePreviewSuspendTarget[];
    try {
      seatsToSuspend = resolveSeatsToSuspend(target.maxSeats, keepUserIds);
    } catch (e) {
      if (e instanceof PlanKeepUserRejection) {
        return rejectionResponse(e);
      }
      throw e;
    }
    const facilityOverflowCount = computeFacilityOverflow(target.maxFacilities);
    const body: ApiResponse<PlanChangePreviewResponse> = {
      success: true,
      data: {
        targetPlan: target.name as AdminUserPlan,
        requiresConfirmation: seatsToSuspend.length > 0 || facilityOverflowCount > 0,
        seatsToSuspend,
        facilityOverflowCount,
      },
    };
    return HttpResponse.json(body);
  }),

  http.patch('/api/admin/plan', async ({ request }) => {
    const payload = (await request.json()) as PlanChangeRequestPayload;
    const target = findCatalogItem(payload.planName);
    if (!target) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: 'PLAN_DATA_INVALID', message: '요금제 데이터에 오류가 있습니다.' },
        },
        { status: 500 },
      );
    }

    const keepUserIds = payload.keepUserIds ?? [];
    let seatsToSuspend: PlanChangePreviewSuspendTarget[];
    try {
      seatsToSuspend = resolveSeatsToSuspend(target.maxSeats, keepUserIds);
    } catch (e) {
      if (e instanceof PlanKeepUserRejection) {
        return rejectionResponse(e);
      }
      throw e;
    }
    const facilityOverflowCount = computeFacilityOverflow(target.maxFacilities);
    const requiresConfirmation = seatsToSuspend.length > 0 || facilityOverflowCount > 0;

    if (requiresConfirmation && payload.confirmOverflow !== true) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: {
            code: 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED',
            message: '하향으로 한도를 넘는 자원이 있어 확인이 필요합니다.',
          },
        },
        { status: 409 },
      );
    }

    // 즉시 변경은 대기 중이던 하향 예약을 자동 취소한다(백엔드 AdminPlanService#changePlan →
    // ScheduledPlanChangeCanceller, #1105 / HAJA-526). 이걸 목에서 재현하지 않으면 GET /api/admin/plan
    // 재조회(useChangePlan onSuccess invalidate, #1191 P2 픽스)가 여전히 이미 취소된 예약을 돌려주게
    // 되어, 회귀 테스트가 실제로는 아무것도 검증하지 못한다.
    mockScheduledChangeState = null;

    const body: ApiResponse<{ plan: { name: string } }> = {
      success: true,
      data: { plan: { name: target.name } },
    };
    return HttpResponse.json(body);
  }),

  // POST /api/admin/plan/scheduled-change — FREE 하향 예약 생성(#1105 / HAJA-526, #1191). 즉시 변경
  // (PATCH)과 동일한 좌석 초과 규칙을 재사용하되, 반영하지 않고 mockScheduledChangeState에 PENDING
  // 건으로만 적재한다 — 이후 GET /api/admin/plan이 이 값을 그대로 돌려준다.
  http.post('/api/admin/plan/scheduled-change', async ({ request }) => {
    const payload = (await request.json()) as AdminScheduledPlanChangeRequestPayload;

    if (payload.planName !== 'FREE') {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: {
            code: 'PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED',
            message: '유료 요금제 간 예약 변경은 지원하지 않습니다.',
          },
        },
        { status: 403 },
      );
    }

    if (mockScheduledChangeState !== null) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: 'PLAN_SCHEDULED_CHANGE_EXISTS', message: '이미 대기 중인 예약이 있습니다.' },
        },
        { status: 409 },
      );
    }

    // 지역 변수로 좁혀둔다 — 프로퍼티 접근을 그대로 두 번 쓰면 TS가 두 함수 호출(findCatalogItem·
    // resolveSeatsToSuspend) 사이의 null 좁힘을 보장하지 못한다.
    const currentPeriodEnd = mockAdminCurrentPlan.currentPeriodEnd;
    if (!currentPeriodEnd) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: {
            code: 'PLAN_SCHEDULE_PERIOD_END_MISSING',
            message: '현재 요금제는 결제 주기가 없어 예약할 수 없습니다.',
          },
        },
        { status: 409 },
      );
    }

    const target = findCatalogItem(payload.planName);
    if (!target) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: 'PLAN_DATA_INVALID', message: '요금제 데이터에 오류가 있습니다.' },
        },
        { status: 500 },
      );
    }

    const keepUserIds = payload.keepUserIds ?? [];
    let seatsToSuspend: PlanChangePreviewSuspendTarget[];
    try {
      seatsToSuspend = resolveSeatsToSuspend(target.maxSeats, keepUserIds);
    } catch (e) {
      if (e instanceof PlanKeepUserRejection) {
        return rejectionResponse(e);
      }
      throw e;
    }
    const facilityOverflowCount = computeFacilityOverflow(target.maxFacilities);
    const requiresConfirmation = seatsToSuspend.length > 0 || facilityOverflowCount > 0;

    if (requiresConfirmation && payload.confirmOverflow !== true) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: {
            code: 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED',
            message: '하향으로 한도를 넘는 자원이 있어 확인이 필요합니다.',
          },
        },
        { status: 409 },
      );
    }

    mockScheduledChangeState = {
      id: mockScheduledChangeIdSeq++,
      targetPlanName: target.name as AdminUserPlan,
      effectiveAt: currentPeriodEnd,
      keepUserIds,
      status: 'PENDING',
    };

    const body: ApiResponse<AdminScheduledPlanChange> = { success: true, data: mockScheduledChangeState };
    return HttpResponse.json(body);
  }),

  // DELETE /api/admin/plan/scheduled-change — 대기 중인 하향 예약 취소(#1105 / HAJA-526, #1191).
  http.delete('/api/admin/plan/scheduled-change', () => {
    if (mockScheduledChangeState === null) {
      return HttpResponse.json(
        {
          success: false,
          data: null,
          error: { code: 'PLAN_SCHEDULED_CHANGE_NOT_FOUND', message: '대기 중인 예약이 없습니다.' },
        },
        { status: 404 },
      );
    }

    const canceled: AdminScheduledPlanChange = { ...mockScheduledChangeState, status: 'CANCELED' };
    mockScheduledChangeState = null;

    const body: ApiResponse<AdminScheduledPlanChange> = { success: true, data: canceled };
    return HttpResponse.json(body);
  }),
];
