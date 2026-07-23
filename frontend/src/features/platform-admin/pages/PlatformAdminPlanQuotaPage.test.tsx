// @vitest-environment jsdom
// PlatformAdminPlanQuotaPage 통합 테스트 — Figma node-id 1206-2639(플랫폼 관리자 기준 화면) 기준.
// 실제 usePlanQuotaUsers 훅 + MSW planQuotaHandlers를 통해 목록 렌더(플랜·남은 기간·상태 포함)·KPI·
// 검색·페이지네이션·에러 상태를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { planQuotaHandlers } from '../api/planQuotaApi.handlers';
import { PLAN_QUOTA_KPI_TEST_ID } from '../components/PlanQuotaKpiCards';
import { PlatformAdminPlanQuotaPage } from './PlatformAdminPlanQuotaPage';

const server = setupServer(...planQuotaHandlers);

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

  it('KPI 카드에 전체 활성 사용자와 평균 쿼터 사용률을 표시한다', async () => {
    renderPage();

    await screen.findByText('김민준');
    const kpi = within(screen.getByTestId(PLAN_QUOTA_KPI_TEST_ID));
    expect(kpi.getByText('전체 활성 사용자')).toBeTruthy();
    expect(kpi.getByText('7')).toBeTruthy();
    expect(kpi.getByText('평균 쿼터 사용률')).toBeTruthy();
  });

  it('첫 페이지에는 페이지 크기(4)만큼만 표시한다', async () => {
    renderPage();

    await screen.findByText('김민준');
    // 5번째 사용자(정하은)는 2페이지에 있어야 한다
    expect(screen.queryByText('정하은')).toBeNull();
    expect(screen.getByText('전체 8명 중 1-4 표시')).toBeTruthy();
  });

  it('행마다 현재 플랜·남은 기간·상태를 표시한다', async () => {
    renderPage();

    await screen.findByText('김민준');
    // 김민준 — Standard 플랜, 245일 남음, 활성
    expect(screen.getAllByText('Standard').length).toBeGreaterThan(0);
    expect(screen.getByText('245일')).toBeTruthy();
    expect(screen.getAllByText('활성').length).toBeGreaterThan(0);
    // 최지우 — 만료 임박(12일), 주의 배지
    expect(screen.getByText('12일')).toBeTruthy();
    expect(screen.getByText('주의')).toBeTruthy();
  });

  it('만료된 사용자는 남은 기간에 "만료됨", 상태에 "만료" 배지를 보여준다', async () => {
    renderPage();

    await screen.findByText('김민준');
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));

    await screen.findByText('윤아린');
    expect(screen.getAllByText('만료됨').length).toBeGreaterThan(0);
    expect(screen.getAllByText('만료').length).toBeGreaterThan(0);
  });

  it('"플랜 정책 설정" 버튼을 누르면 정책 모달이 열리고, 닫으면 사라진다', async () => {
    renderPage();

    await screen.findByText('김민준');
    fireEvent.click(screen.getByRole('button', { name: '플랜 정책 설정' }));

    expect(await screen.findByText('플랜 정책 설정 (Plan Policy Settings)')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    await waitFor(() => {
      expect(screen.queryByText('플랜 정책 설정 (Plan Policy Settings)')).toBeNull();
    });
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
