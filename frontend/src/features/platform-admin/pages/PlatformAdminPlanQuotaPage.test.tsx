// @vitest-environment jsdom
// PlatformAdminPlanQuotaPage 통합 테스트 — features/admin/pages/PlanQuotaPage.test.tsx(#508)를
// 그대로 옮긴 것(#625). 실제 usePlanQuotaUsers 훅 + MSW planQuotaHandlers를 통해 목록 렌더·KPI·
// 검색·페이지네이션·에러 상태를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { adminPlanHandlers } from '../api/adminPlanApi.handlers';
import { planQuotaHandlers } from '../api/planQuotaApi.handlers';
import { PLAN_QUOTA_KPI_TEST_ID } from '../components/PlanQuotaKpiCards';
import { PlatformAdminPlanQuotaPage } from './PlatformAdminPlanQuotaPage';

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
        <PlatformAdminPlanQuotaPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PlatformAdminPlanQuotaPage (통합 테스트)', () => {
  it('목록을 불러와 사용자명·이메일·쿼터 사용률을 렌더링한다', async () => {
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
    // 5번째 사용자(정하은)는 2페이지에 있어야 한다
    expect(screen.queryByText('정하은')).toBeNull();
    expect(screen.getByText('전체 8명 중 1-4 표시')).toBeTruthy();
  });

  it('현재 플랜 카드는 고정값을 보여주고, 페이지를 넘겨도 바뀌지 않는다', async () => {
    renderPage();

    expect(await screen.findByText('Standard')).toBeTruthy();

    // 2페이지로 이동해도(한서준=null인 사용자가 보여도) 카드는 그대로 유지된다
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    await screen.findByText('한서준');
    expect(screen.getByText('Standard')).toBeTruthy();
  });

  it('카탈로그의 priceMonthly가 null이어도 현재 플랜 카드가 크래시 없이 렌더된다', async () => {
    server.use(
      http.get('/api/platform-admin/plans', () =>
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
      http.get('/api/platform-admin/plans-quota', () =>
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

  it('활성 구독이 없으면 현재 플랜 카드에 안내 문구를 보여준다', async () => {
    server.use(
      http.get('/api/platform-admin/plans-quota', () =>
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
  });

  it('검색어를 입력하면 해당 사용자만 조회한다', async () => {
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
      http.get('/api/platform-admin/plans-quota', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'SERVER_ERROR', message: '서버 오류' } },
          { status: 500 },
        ),
      ),
    );
    renderPage();

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    const kpi = within(screen.getByTestId(PLAN_QUOTA_KPI_TEST_ID));
    expect(kpi.getAllByText('-').length).toBeGreaterThan(0);
  });
});
