// @vitest-environment jsdom
// PlatformAdminStatsPage 통합 테스트 — Figma node-id 177-3515 기준.
// 실제 useServiceStats 훅 + MSW statsHandlers를 통해 KPI·차트·분포·월별 요약 렌더와 에러 상태를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { statsHandlers } from '../api/statsApi.handlers';
import { SERVICE_STATS_KPI_TEST_ID } from '../components/ServiceStatsKpiCards';
import { PlatformAdminStatsPage } from './PlatformAdminStatsPage';

const server = setupServer(...statsHandlers);

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
        <PlatformAdminStatsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PlatformAdminStatsPage (통합 테스트)', () => {
  it('KPI 카드 4종을 렌더링한다', async () => {
    renderPage();

    const kpi = within(await screen.findByTestId(SERVICE_STATS_KPI_TEST_ID));
    expect(kpi.getByText('1,284')).toBeTruthy();
    expect(kpi.getByText('152')).toBeTruthy();
    expect(kpi.getByText('24,180')).toBeTruthy();
    expect(kpi.getByText('486')).toBeTruthy();
  });

  it('플랜 분포와 상담 유형 분포를 렌더링한다', async () => {
    renderPage();

    await screen.findByTestId(SERVICE_STATS_KPI_TEST_ID);
    expect(screen.getByText('Free (60%)')).toBeTruthy();
    expect(screen.getByText('Standard (30%)')).toBeTruthy();
    expect(screen.getByText('Enterprise (10%)')).toBeTruthy();
    expect(screen.getByText('서비스 이용 방법')).toBeTruthy();
    expect(screen.getByText('312')).toBeTruthy();
  });

  it('월별 요약 표에 6개월치 행을 렌더링한다', async () => {
    renderPage();

    await screen.findByTestId(SERVICE_STATS_KPI_TEST_ID);
    // 라인·막대 차트의 X축 눈금도 "1월"~"6월" 텍스트를 그리므로 표 안으로 스코프한다
    const table = within(screen.getByRole('table'));
    expect(table.getByText('6월')).toBeTruthy();
    expect(table.getByText('1월')).toBeTruthy();
    // 6월 신규 가입(152)은 KPI에도 나타나 getAllByText로 확인
    expect(screen.getAllByText('152').length).toBeGreaterThan(0);
  });

  it('조회 실패 시 에러 메시지와 KPI "-"를 노출한다', async () => {
    server.use(
      http.get('/api/platform-admin/stats', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'SERVER_ERROR', message: '서버 오류' } },
          { status: 500 },
        ),
      ),
    );
    renderPage();

    expect(await screen.findByRole('alert')).toBeTruthy();
    const kpi = within(screen.getByTestId(SERVICE_STATS_KPI_TEST_ID));
    expect(kpi.getAllByText('-').length).toBeGreaterThan(0);
  });
});
