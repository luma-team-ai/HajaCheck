// @vitest-environment jsdom
// PlanQuotaPage 통합 테스트 — 실제 usePlanQuotaUsers 훅 + MSW planQuotaHandlers를 통해
// 목록 렌더·KPI·행 선택(현재 플랜 연동)·검색·페이지네이션·에러 상태를 검증한다.
//
// 스코프(2026-07-21 확정): "현재 플랜" 카드는 표 행 선택과 무관하게 로그인한 관리자의 회사
// 플랜(stats.companyPlan) 고정값을 보여준다 — 행마다 카드가 바뀌는 게 아니라, 어떤 멤버 행을
// 봐도 항상 같은 회사 플랜이 표시되는지를 검증한다(planQuotaUsers.mock.ts 참조).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { adminPlanHandlers, resetAdminPlanScheduleMockStore } from '../api/adminPlanApi.handlers';
import { planQuotaHandlers } from '../api/planQuotaApi.handlers';
import { PLAN_QUOTA_KPI_TEST_ID } from '../components/PlanQuotaKpiCards';
import { mockPlanQuotaUsers } from '../mocks/planQuotaUsers.mock';
import { PLAN_QUOTA_DEFAULT_PAGE_SIZE } from '../planQuota.constants';
import { useAuthStore } from '../../auth/store/authStore';
import type { User } from '../../auth/types';
import { PlanQuotaPage } from './PlanQuotaPage';

const server = setupServer(...planQuotaHandlers, ...adminPlanHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // 하향 예약(#1105 / HAJA-526, #1191) 목 상태는 resetHandlers()로 초기화되지 않는 모듈 스코프
  // 상태라 매 테스트마다 명시적으로 되돌린다(mypageApi.handlers.ts와 동일 패턴).
  resetAdminPlanScheduleMockStore();
  cleanup();
  useAuthStore.setState({ user: null });
});
afterAll(() => server.close());

const OWNER_USER: User = {
  id: 1,
  email: 'minjun.kim@company.com',
  name: '김민준',
  role: 'ADMIN',
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-01-01T00:00:00',
  companyName: '회사',
  status: 'ACTIVE',
};

// F-1/F-2/F-3/F-13 시나리오가 공유하는 STANDARD(좌석3) 하향 셋업 — 기존 "확인 모달에서 유지할
// 구성원을 직접 선택하면" 테스트와 동일하게 현재 플랜을 ENTERPRISE로 override해 좌석 여유가 있는
// STANDARD로 내리는 상황을 재현한다(FREE는 좌석 1이라 선택 UX 자체가 나오지 않는다).
function useStandardDowngradeFixture(): void {
  server.use(
    http.get('/api/admin/plan-quota', ({ request }) => {
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? 1);
      const size = Number(url.searchParams.get('size') ?? PLAN_QUOTA_DEFAULT_PAGE_SIZE);
      const start = (page - 1) * size;
      return HttpResponse.json({
        success: true,
        data: {
          content: mockPlanQuotaUsers.slice(start, start + size),
          page,
          size,
          totalElements: mockPlanQuotaUsers.length,
          stats: { activeUsers: 7, totalQuotaUsagePercent: 29, companyPlan: 'ENTERPRISE' },
        },
      });
    }),
  );
}

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PlanQuotaPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PlanQuotaPage (통합 테스트)', () => {
  it('목록을 불러와 멤버명·이메일·쿼터 사용률을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('김민준')).toBeTruthy();
    expect(screen.getByText('minjun.kim@company.com')).toBeTruthy();
    // 29%(정상)·94%(경고) 사용률이 표에 나타난다
    expect(screen.getByText('29%')).toBeTruthy();
    expect(screen.getByText('94%')).toBeTruthy();
  });

  it('KPI 카드에 전체 활성 사용자와 쿼터 사용률을 표시한다', async () => {
    renderPage();

    await screen.findByText('김민준');
    const kpi = within(screen.getByTestId(PLAN_QUOTA_KPI_TEST_ID));
    expect(kpi.getByText('전체 활성 사용자')).toBeTruthy();
    expect(kpi.getByText('7')).toBeTruthy();
    expect(kpi.getByText('전체 쿼터 사용률')).toBeTruthy();
  });

  it('첫 페이지에는 페이지 크기(4)만큼만 표시한다', async () => {
    renderPage();

    await screen.findByText('김민준');
    // 5번째 멤버(정하은)는 2페이지에 있어야 한다
    expect(screen.queryByText('정하은')).toBeNull();
    expect(screen.getByText('전체 8명 중 1-4 표시')).toBeTruthy();
  });

  it('현재 플랜 카드는 회사 플랜 고정값을 보여주고, 페이지를 넘겨도 바뀌지 않는다', async () => {
    renderPage();

    // 회사 플랜(companyPlan=STANDARD)이 표시된다 — 특정 행을 선택해야 나오는 게 아니다
    expect(await screen.findByText('Standard')).toBeTruthy();

    // 2페이지로 이동해도(한서준=null인 멤버가 보여도) 카드는 그대로 회사 플랜을 유지한다
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    await screen.findByText('한서준');
    expect(screen.getByText('Standard')).toBeTruthy();
  });

  it('카탈로그의 priceMonthly가 null이어도 현재 플랜 카드가 크래시 없이 렌더된다', async () => {
    // plans.price_monthly는 DDL상 nullable — PR머신 리뷰 P2(카드가 detail.priceMonthly.toLocaleString()로
    // 크래시하던 계약 불일치)의 회귀 테스트.
    server.use(
      http.get('/api/admin/plans', () =>
        HttpResponse.json({
          success: true,
          data: {
            plans: [
              {
                id: 2,
                name: 'STANDARD',
                maxFacilities: 10,
                maxMonthlyAnalyses: 1000,
                maxSeats: 3,
                hasPdfWatermark: false,
                hasCounselorAccess: true,
                hasAiAddon: true,
                priceMonthly: null,
              },
            ],
          },
        }),
      ),
    );
    renderPage();

    expect(await screen.findByText('가격 문의')).toBeTruthy();
  });

  it('ENTERPRISE의 maxSeats가 null이면 무제한 좌석을 포함 기능으로 렌더링한다', async () => {
    server.use(
      http.get('/api/admin/plan-quota', () =>
        HttpResponse.json({
          success: true,
          data: {
            content: [],
            page: 1,
            size: 4,
            totalElements: 0,
            stats: { activeUsers: 0, totalQuotaUsagePercent: 0, companyPlan: 'ENTERPRISE' },
          },
        }),
      ),
    );
    renderPage();

    expect(await screen.findByText('Enterprise')).toBeTruthy();
    expect(screen.getByText('점검자 좌석 무제한')).toBeTruthy();
  });

  it('회사에 활성 구독이 없으면 현재 플랜 카드와 페이지 안내 문구를 함께 보여준다', async () => {
    // #887: companyPlan=null은 정상 응답(200)이라 isError 배너(role=alert)가 아니라
    // role=status인 별도 안내로 렌더돼야 한다 — 조회 실패와 混同되지 않는지 확인.
    server.use(
      http.get('/api/admin/plan-quota', () =>
        HttpResponse.json({
          success: true,
          data: {
            content: [],
            page: 1,
            size: 4,
            totalElements: 0,
            stats: { activeUsers: 0, totalQuotaUsagePercent: 0, companyPlan: null },
          },
        }),
      ),
    );
    renderPage();

    expect(await screen.findByText('활성 구독 없음')).toBeTruthy();
    expect(
      await screen.findByText(
        '현재 회사에 활성화된 플랜 구독이 없습니다. 플랜을 등록하면 멤버별 쿼터 사용량이 표시됩니다.',
      ),
    ).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('검색어를 입력하면 해당 멤버만 조회한다', async () => {
    renderPage();

    await screen.findByText('김민준');
    fireEvent.change(screen.getByLabelText('사용자 검색'), { target: { value: '박도윤' } });

    await waitFor(() => {
      expect(screen.queryByText('김민준')).toBeNull();
    });
    expect(screen.getByText('박도윤')).toBeTruthy();
  });

  it('검색 결과가 없으면 빈 안내를 보여준다', async () => {
    renderPage();

    await screen.findByText('김민준');
    fireEvent.change(screen.getByLabelText('사용자 검색'), {
      target: { value: '존재하지않는계정' },
    });

    expect(await screen.findByText('조건에 맞는 사용자가 없습니다')).toBeTruthy();
  });

  it('조회 실패 시 에러 메시지·다시 시도 버튼과 KPI "-"를 노출한다', async () => {
    server.use(
      http.get('/api/admin/plan-quota', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'SERVER_ERROR', message: '서버 오류' } },
          { status: 500 },
        ),
      ),
    );
    renderPage();

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    // KPI는 사라지지 않고 "-"로 자리를 지킨다
    const kpi = within(screen.getByTestId(PLAN_QUOTA_KPI_TEST_ID));
    expect(kpi.getAllByText('-').length).toBeGreaterThan(0);
  });
});

// 플랜 변경 흐름(#890 Phase 1 확인 UX + Phase 2 keepUserIds) — PlanChangeControl + PlanDowngradeConfirmModal.
// adminPlanApi.handlers.ts의 목은 planQuotaUsers.mock.ts의 활성 멤버 7명(plan !== null, 1번 김민준을
// owner로 가정)으로 좌석 초과를 재현한다 — FREE(좌석 1)로 내리면 owner만 남고 나머지 6명이 정지된다.
describe('PlanQuotaPage — 플랜 변경(#890)', () => {
  it('requiresConfirmation=false(초과 없음)면 확인 모달 없이 즉시 반영된다', async () => {
    renderPage();
    await screen.findByText('김민준');

    // 현재 STANDARD → ENTERPRISE(무제한)는 넘치는 자원이 없다.
    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'ENTERPRISE' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    // 확인 모달 없이 바로 처리 — 선택값이 플레이스홀더로 리셋된다.
    await waitFor(() => {
      expect((screen.getByLabelText('변경할 요금제') as HTMLSelectElement).value).toBe('');
    });
    expect(screen.queryByText(/플랜으로 변경/)).toBeNull();
  });

  it('requiresConfirmation=true(좌석 초과)면 확인 모달에 정지될 구성원 이름·이메일이 뜬다', async () => {
    renderPage();
    await screen.findByText('김민준');

    // STANDARD → FREE(좌석 1)는 owner 외 전원이 넘친다.
    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'FREE' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    expect(await screen.findByText('Free 플랜으로 변경')).toBeTruthy();
    // 이서연은 아래 표(page1)에도 같은 이름이 렌더되므로 모달(dialog) 범위로 좁혀 조회한다.
    // 미리보기 자체가 비동기 조회라 findByText로 로딩이 끝나길 기다린다.
    const dialog = within(screen.getByRole('dialog'));
    expect(await dialog.findByText('정지될 구성원 6명')).toBeTruthy();
    expect(dialog.getByText('이서연')).toBeTruthy();
    expect(dialog.getByText('seoyeon.lee@company.com')).toBeTruthy();
  });

  it('확인 모달에서 유지할 구성원을 직접 선택하면 그 인원이 정지 대상에서 빠진다', async () => {
    // FREE(좌석 1)는 owner 외 아무도 더 유지할 여유가 없어(추가 선택=좌석 초과) 선택 교체를 보여줄 수
    // 없다 — 좌석에 여유가 있는 STANDARD(좌석 3)로 내리는 시나리오로 재현한다(현재 플랜을 ENTERPRISE로
    // override). 기본 선택(id 오름차순)은 owner(1)+이서연(2)+박도윤(3) 유지, 나머지 4명 정지다.
    // 모달의 유지 대상 선택 목록(size=100)과 페이지의 표(size=4)가 같은 엔드포인트를 쓰므로,
    // 두 호출 모두 실제 멤버 데이터를 그대로 페이징해 돌려주고 companyPlan만 override한다.
    useStandardDowngradeFixture();
    renderPage();
    await screen.findByText('Enterprise');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'STANDARD' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    await screen.findByText('정지될 구성원 4명');
    // 롤 목록은 회사 전 구성원을 항상 보여준다(체크=유지, 해제=정지) — 박도윤·최지우는 아래 표(page1)
    // 에도 같은 이름이 렌더되므로 모달(dialog) 범위로 좁혀 조회하고, "정지 여부"는 체크박스 상태로
    // 검증한다(이름·이메일 자체는 유지 대상이어도 정지 대상이어도 항상 렌더되므로 존재 유무로는
    // 판별할 수 없다).
    const dialog = within(screen.getByRole('dialog'));
    const doyoonCheckbox = within(dialog.getByText('박도윤').closest('label') as HTMLElement).getByRole(
      'checkbox',
    ) as HTMLInputElement;
    const jiwooCheckbox = within(dialog.getByText('최지우').closest('label') as HTMLElement).getByRole(
      'checkbox',
    ) as HTMLInputElement;

    // 기본값(id 오름차순 자동 선정): owner(1)+이서연(2)+박도윤(3) 유지, 최지우(4번째)는 정지 대상.
    expect(doyoonCheckbox.checked).toBe(true);
    expect(jiwooCheckbox.checked).toBe(false);

    // 박도윤을 해제하고 최지우를 유지로 선택한다 — 유지 인원 수는 그대로(3명)이므로 좌석 한도를
    // 넘기지 않는다.
    fireEvent.click(doyoonCheckbox);
    fireEvent.click(jiwooCheckbox);

    expect(doyoonCheckbox.checked).toBe(false);
    expect(jiwooCheckbox.checked).toBe(true);
  });

  it('미리보기 이후 확인이 필요해지는 경합(409)이 나면 확인 모달로 방어적으로 전환한다', async () => {
    // 미리보기(change-preview)는 초과 없음을 반환하지만, 실제 PATCH 시점엔 인원이 늘어 서버가
    // 409(PLAN_DOWNGRADE_CONFIRMATION_REQUIRED)를 돌려주는 경합 상황을 재현한다. 메시지 문자열이
    // 아니라 에러 코드로 분기해야 한다(핸드오프 §1-4).
    server.use(
      http.get('/api/admin/plan/change-preview', () =>
        HttpResponse.json({
          success: true,
          data: {
            targetPlan: 'ENTERPRISE',
            requiresConfirmation: false,
            seatsToSuspend: [],
            facilityOverflowCount: 0,
          },
        }),
      ),
      http.patch('/api/admin/plan', () =>
        HttpResponse.json(
          {
            success: false,
            data: null,
            error: {
              code: 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED',
              message: '하향으로 한도를 넘는 자원이 있어 확인이 필요합니다.',
            },
          },
          { status: 409 },
        ),
      ),
    );
    renderPage();
    await screen.findByText('김민준');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'ENTERPRISE' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    // 문자열 매칭이 아니라 에러 코드 분기로 확인 모달이 열린다.
    expect(await screen.findByText('Enterprise 플랜으로 변경')).toBeTruthy();
  });
});

// 재검토 라운드(#930 F-1/F-2/F-3/F-13) — 리뷰에서 지적된 "주 사용 경로가 항상 403 데드엔드",
// owner 체크박스 미고정, 로딩 중 전원 체크 오표시, 시설물만 초과인데도 선택 UI가 나오는 문제를
// 실제 상호작용으로 재현·검증한다.
describe('PlanQuotaPage — 플랜 하향 확인 모달 재검토(#930 2차)', () => {
  it('owner 행은 항상 체크·비활성이고 전용 안내 문구를 보여준다(F-2)', async () => {
    useAuthStore.setState({ user: OWNER_USER });
    useStandardDowngradeFixture();
    renderPage();
    await screen.findByText('Enterprise');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'STANDARD' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    const dialog = within(await screen.findByRole('dialog'));
    await dialog.findByText('정지될 구성원 4명');

    const ownerNote = dialog.getByText('회사 소유자는 항상 유지됩니다');
    const ownerCheckbox = within(ownerNote.closest('label') as HTMLElement).getByRole(
      'checkbox',
    ) as HTMLInputElement;
    expect(ownerCheckbox.checked).toBe(true);
    expect(ownerCheckbox.disabled).toBe(true);

    // owner 행 자체를 클릭해도(방어) 상태가 바뀌지 않는다.
    fireEvent.click(ownerCheckbox);
    expect(ownerCheckbox.checked).toBe(true);
  });

  it('좌석 카운터를 보여주고 한도 도달 시 미체크 체크박스를 disabled 처리한다(F-1)', async () => {
    useStandardDowngradeFixture();
    renderPage();
    await screen.findByText('Enterprise');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'STANDARD' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    const dialog = within(await screen.findByRole('dialog'));
    await dialog.findByText('정지될 구성원 4명');

    // 자동 선정이 이미 좌석(3)을 꽉 채운 상태 — "현재 선택 3 / 최대 3명"이 화면에 드러나야 하고,
    // 이 한도 안에서는 미체크 상태인 구성원(최지우)의 체크박스가 disabled라 클릭해도 403으로
    // 이어지는 원천 자체가 막힌다.
    expect(dialog.getByText('현재 유지 선택 3 / 최대 3명 (회사 소유자 1석 포함)')).toBeTruthy();

    const jiwooCheckbox = within(dialog.getByText('최지우').closest('label') as HTMLElement).getByRole(
      'checkbox',
    ) as HTMLInputElement;
    expect(jiwooCheckbox.checked).toBe(false);
    expect(jiwooCheckbox.disabled).toBe(true);

    // 이미 유지 중인(체크된) 구성원은 한도 도달과 무관하게 여전히 해제할 수 있어야 한다.
    const doyoonCheckbox = within(dialog.getByText('박도윤').closest('label') as HTMLElement).getByRole(
      'checkbox',
    ) as HTMLInputElement;
    expect(doyoonCheckbox.disabled).toBe(false);
  });

  it('시설물만 초과(정지 대상 0명)면 유지 대상 선택 목록을 감추고 시설물 안내만 보여준다(F-13)', async () => {
    // 현재 플랜(기본 목=STANDARD)과 다른 요금제를 골라야 select 옵션에 뜬다 — ENTERPRISE를 선택한다.
    server.use(
      http.get('/api/admin/plan/change-preview', () =>
        HttpResponse.json({
          success: true,
          data: {
            targetPlan: 'ENTERPRISE',
            requiresConfirmation: true,
            seatsToSuspend: [],
            facilityOverflowCount: 3,
          },
        }),
      ),
    );
    renderPage();
    await screen.findByText('김민준');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'ENTERPRISE' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    const dialog = within(await screen.findByRole('dialog'));
    await dialog.findByText('정지될 구성원 0명');

    // 시설물 안내는 그대로 보인다.
    expect(dialog.getByText(/읽기 전용이 되는 시설물: 3개/)).toBeTruthy();
    // 유지 대상 선택 목록(체크박스 롤)은 통째로 감춰진다 — 조작해도 아무 의미가 없기 때문.
    expect(dialog.queryByRole('checkbox')).toBeNull();
  });

  it('선택을 바꿔 재조회하는 동안 확정 버튼이 비활성화되고 이전 미리보기가 사라지지 않는다(F-3)', async () => {
    useStandardDowngradeFixture();
    // PlanChangeControl은 모달을 열지 결정하려고 previewChange를 직접 한 번 호출하고(선택 없음),
    // 모달이 열리면 usePlanChangePreview 훅이 같은 파라미터로 다시 조회한다 — 즉 keepUserIds가
    // 없는 호출은 최소 두 번 일어난다. 이 테스트가 지연시켜야 할 호출은 "사용자가 유지 대상을
    // 바꿔서 keepUserIds가 실린 재조회" 하나뿐이므로, 호출 순서가 아니라 keepUserIds 유무로 판별한다.
    const pendingSelectionRequest: { resolve: (() => void) | null } = { resolve: null };
    server.use(
      http.get('/api/admin/plan/change-preview', async ({ request }) => {
        const url = new URL(request.url);
        const hasSelection = url.searchParams.getAll('keepUserIds').length > 0;
        if (hasSelection) {
          await new Promise<void>((resolve) => {
            pendingSelectionRequest.resolve = resolve;
          });
        }
        return HttpResponse.json({
          success: true,
          data: {
            targetPlan: 'STANDARD',
            requiresConfirmation: true,
            seatsToSuspend: hasSelection
              ? [{ userId: 4, name: '최지우', email: 'jiwoo.choi@company.com' }]
              : [
                  { userId: 4, name: '최지우', email: 'jiwoo.choi@company.com' },
                  { userId: 5, name: '정하은', email: 'haeun.jung@company.com' },
                  { userId: 6, name: '강시우', email: 'siwoo.kang@company.com' },
                  { userId: 7, name: '윤아린', email: 'arin.yoon@company.com' },
                ],
            facilityOverflowCount: 0,
          },
        });
      }),
    );
    renderPage();
    await screen.findByText('Enterprise');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'STANDARD' } });
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    const dialog = within(await screen.findByRole('dialog'));
    await dialog.findByText('정지될 구성원 4명');

    // 이미 유지 중인 박도윤을 해제 — keepUserIds가 실린 재조회(지연 응답)를 트리거한다.
    const doyoonCheckbox = within(dialog.getByText('박도윤').closest('label') as HTMLElement).getByRole(
      'checkbox',
    ) as HTMLInputElement;
    fireEvent.click(doyoonCheckbox);
    expect(doyoonCheckbox.checked).toBe(false);

    // 재조회가 진행 중인 동안(isFetching) — 이전 미리보기 값이 유지되고(깜빡이지 않음), 확정 버튼은
    // stale 미리보기로 확정되지 않도록 비활성화된다.
    await waitFor(() => {
      expect(pendingSelectionRequest.resolve).not.toBeNull();
    });
    expect(dialog.getByText('정지될 구성원 4명')).toBeTruthy();
    const confirmButton = dialog.getByRole('button', { name: '변경 확정' }) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(true);
    // 목록 자체는 사라지지 않는다(같은 DOM 참조가 계속 유효) — 재조회 중에도 상호작용이 끊기지 않는다.
    expect(dialog.getByText('박도윤')).toBeTruthy();

    pendingSelectionRequest.resolve?.();

    await waitFor(() => {
      expect((dialog.getByRole('button', { name: '변경 확정' }) as HTMLButtonElement).disabled).toBe(
        false,
      );
    });
    expect(dialog.getByText('정지될 구성원 1명')).toBeTruthy();
  });
});

// 플랜 하향 예약(#1105 / HAJA-526 백엔드 dev 완결, #1191 FE 배선) — 현재 플랜(STANDARD)에서 FREE로
// 하향할 때만 "즉시/다음 결제일" 선택지가 나타난다. GET /api/admin/plan(useAdminCurrentPlan)의
// currentPeriodEnd·scheduledChange를 그대로 쓴다(mockAdminCurrentPlan 기본값: currentPeriodEnd
// 2026-08-21T09:00:00Z, scheduledChange null).
describe('PlanQuotaPage — 플랜 하향 예약(#1191)', () => {
  it('FREE를 선택할 때만 적용 시점(즉시/다음 결제일) 선택지가 나타난다', async () => {
    renderPage();
    await screen.findByText('김민준');

    // STANDARD → ENTERPRISE(상향)는 예약 대상이 아니다 — 선택지 자체가 없다.
    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'ENTERPRISE' } });
    expect(screen.queryByRole('radiogroup', { name: '적용 시점' })).toBeNull();

    // STANDARD → FREE(하향)는 즉시/예약 선택지가 나타난다.
    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'FREE' } });
    expect(await screen.findByRole('radiogroup', { name: '적용 시점' })).toBeTruthy();
    expect(screen.getByLabelText('즉시 적용')).toBeTruthy();
    expect(screen.getByLabelText(/다음 결제일 적용/)).toBeTruthy();
  });

  it('다음 결제일 적용으로 예약하면 확인 모달에 적용 예정일·FREE 1석 경고가 뜨고, 확정 후 카드에 예약 배너가 나타난다', async () => {
    renderPage();
    await screen.findByText('김민준');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'FREE' } });
    await screen.findByRole('radiogroup', { name: '적용 시점' });
    fireEvent.click(screen.getByLabelText(/다음 결제일 적용/));
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    // 즉시 변경과 다른 제목 — "…변경 예약"
    expect(await screen.findByText('Free 플랜으로 변경 예약')).toBeTruthy();
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText(/2026-08-21부터 적용됩니다/)).toBeTruthy();
    expect(dialog.getByText(/FREE 요금제는 좌석이 1개입니다/)).toBeTruthy();
    // 정지 인원 문구도 "적용 시점에" 미래형으로 바뀐다(즉시 하향의 "정지될 구성원"과 구분).
    await dialog.findByText('적용 시점에 정지될 구성원 6명');

    fireEvent.click(dialog.getByRole('button', { name: '예약 확정' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    // CurrentPlanCard 배너 — GET /api/admin/plan이 재조회되어 scheduledChange를 반영한다.
    expect(await screen.findByText('Free로 변경 예정 (2026-08-21)')).toBeTruthy();
    expect(screen.getByText(/그때까지 현재 요금제를 그대로 사용합니다/)).toBeTruthy();

    // 배너의 취소 버튼으로 예약을 취소하면 배너가 사라진다.
    fireEvent.click(screen.getByRole('button', { name: '예약 취소' }));
    await waitFor(() => {
      expect(screen.queryByText('Free로 변경 예정 (2026-08-21)')).toBeNull();
    });
  });

  it('현재 요금제에 결제 주기(currentPeriodEnd)가 없으면 다음 결제일 적용 선택지가 비활성화된다', async () => {
    server.use(
      http.get('/api/admin/plan', () =>
        HttpResponse.json({
          success: true,
          data: {
            subscriptionId: 1,
            plan: { id: 2, name: 'STANDARD', maxFacilities: 10, maxMonthlyAnalyses: 1000, maxSeats: 3,
              hasPdfWatermark: false, hasCounselorAccess: true, hasAiAddon: true, priceMonthly: 29000 },
            status: 'ACTIVE',
            startedAt: '2026-01-01T00:00:00Z',
            currentPeriodEnd: null,
            scheduledChange: null,
            usage: { analyzedImageCount: 0, analysisRequestCount: 0, facilityCount: 0, seatCount: 1, period: '2026-07-01' },
          },
        }),
      ),
    );
    renderPage();
    await screen.findByText('김민준');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'FREE' } });
    const scheduledRadio = (await screen.findByLabelText(
      /다음 결제일 적용/,
    )) as HTMLInputElement;
    await screen.findByText('현재 요금제는 결제 주기가 없어 예약할 수 없습니다.');
    expect(scheduledRadio.disabled).toBe(true);
  });

  it('예약 생성이 서버에서 409(PLAN_SCHEDULED_CHANGE_EXISTS)로 거절되면 확인 모달에 안내 문구가 뜬다', async () => {
    // 이미 대기 중인 예약이 있는 경합 상황(신청 사이 다른 관리자가 먼저 예약)을 재현한다 —
    // UI는 hasPendingSchedule로 선택지를 미리 막지만, 그 판정 이후 실제 요청 사이의 경합은
    // 서버 응답(에러 코드)으로만 방어할 수 있다.
    server.use(
      http.post('/api/admin/plan/scheduled-change', () =>
        HttpResponse.json(
          {
            success: false,
            data: null,
            error: { code: 'PLAN_SCHEDULED_CHANGE_EXISTS', message: '이미 대기 중인 예약이 있습니다.' },
          },
          { status: 409 },
        ),
      ),
    );
    renderPage();
    await screen.findByText('김민준');

    fireEvent.change(screen.getByLabelText('변경할 요금제'), { target: { value: 'FREE' } });
    await screen.findByRole('radiogroup', { name: '적용 시점' });
    fireEvent.click(screen.getByLabelText(/다음 결제일 적용/));
    fireEvent.click(screen.getByRole('button', { name: '변경' }));

    const dialog = within(await screen.findByRole('dialog'));
    await dialog.findByText('적용 시점에 정지될 구성원 6명');
    fireEvent.click(dialog.getByRole('button', { name: '예약 확정' }));

    expect(await dialog.findByText('이미 예약된 변경이 있습니다.')).toBeTruthy();
  });
});
