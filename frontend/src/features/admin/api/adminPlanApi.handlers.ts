import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockPlanQuotaUsers } from '../mocks/planQuotaUsers.mock';
import type {
  AdminPlanCatalogResponse,
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

// ── 플랜 변경 미리보기·실행 목(#890 Phase 1/2) ──
// planQuotaUsers.mock.ts 의 활성 멤버(plan !== null, 7명 — 1번 김민준을 "회사 owner"로 가정)로 좌석
// 초과를 재현한다. 시설물 보유량은 이 목에 없는 값이라 임의 상수로 둔다(화면 렌더 확인 목적).
const MOCK_ACTIVE_MEMBER_IDS = mockPlanQuotaUsers.filter((user) => user.plan !== null).map((user) => user.id);
const MOCK_OWNER_ID = MOCK_ACTIVE_MEMBER_IDS[0];
const MOCK_OWNED_FACILITY_COUNT = 12;

function findCatalogItem(planName: string | null) {
  return mockAdminPlanCatalog.plans.find((item) => item.name === planName);
}

// 백엔드 PlanDowngradeService#resolveSeatsToSuspend 와 동일한 규칙(owner 항상 유지, keepUserIds
// 미지정 시 id 오름차순 자동 선정)을 목에서도 재현해, 실 API로 교체해도 화면 동작이 그대로 남게 한다.
function computeSeatsToSuspend(maxSeats: number | null, keepUserIds: number[]): PlanChangePreviewSuspendTarget[] {
  if (maxSeats === null || MOCK_ACTIVE_MEMBER_IDS.length <= maxSeats) {
    return [];
  }
  const keep = new Set<number>([MOCK_OWNER_ID, ...keepUserIds]);
  if (keepUserIds.length === 0) {
    for (const id of MOCK_ACTIVE_MEMBER_IDS) {
      if (keep.size >= maxSeats) {
        break;
      }
      keep.add(id);
    }
  }
  return MOCK_ACTIVE_MEMBER_IDS.filter((id) => !keep.has(id)).map((id) => {
    const user = mockPlanQuotaUsers.find((candidate) => candidate.id === id);
    return { userId: id, name: user?.name ?? `구성원${id}`, email: user?.email ?? '' };
  });
}

function computeFacilityOverflow(maxFacilities: number | null): number {
  return maxFacilities === null ? 0 : Math.max(0, MOCK_OWNED_FACILITY_COUNT - maxFacilities);
}

export const adminPlanHandlers = [
  http.get('/api/admin/plans', () => {
    const body: ApiResponse<AdminPlanCatalogResponse> = { success: true, data: mockAdminPlanCatalog };
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

    const seatsToSuspend = computeSeatsToSuspend(target.maxSeats, keepUserIds);
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
    const seatsToSuspend = computeSeatsToSuspend(target.maxSeats, keepUserIds);
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

    const body: ApiResponse<{ plan: { name: string } }> = {
      success: true,
      data: { plan: { name: target.name } },
    };
    return HttpResponse.json(body);
  }),
];
