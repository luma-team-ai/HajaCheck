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
import { adminPlanHandlers } from '../api/adminPlanApi.handlers';
import { planQuotaHandlers } from '../api/planQuotaApi.handlers';
import { PLAN_QUOTA_KPI_TEST_ID } from '../components/PlanQuotaKpiCards';
import { mockPlanQuotaUsers } from '../mocks/planQuotaUsers.mock';
import { PLAN_QUOTA_DEFAULT_PAGE_SIZE } from '../planQuota.constants';
import { PlanQuotaPage } from './PlanQuotaPage';

const server = setupServer(...planQuotaHandlers, ...adminPlanHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

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
    server.use(
      http.get('/api/admin/plan-quota', ({ request }) => {
        // 모달의 유지 대상 선택 목록(size=100)과 페이지의 표(size=4)가 같은 엔드포인트를 쓰므로,
        // 두 호출 모두 실제 멤버 데이터를 그대로 페이징해 돌려주고 companyPlan만 override한다.
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
