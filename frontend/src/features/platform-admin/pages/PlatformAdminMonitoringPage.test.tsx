// @vitest-environment jsdom
// PlatformAdminMonitoringPage 통합 테스트 — Figma node-id 1-404 기준.
// 실제 useSystemMonitoring 훅 + MSW monitoringHandlers를 통해 서버 상태·잡 큐·HF API 사용량·에러
// 로그 렌더와 에러 상태를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { monitoringHandlers } from '../api/monitoringApi.handlers';
import { JOB_QUEUE_TEST_ID } from '../components/AnalysisJobQueueCard';
import { ERROR_LOG_TABLE_TEST_ID } from '../components/ErrorLogTable';
import { SERVER_HEALTH_TEST_ID } from '../components/ServerHealthCards';
import { PlatformAdminMonitoringPage } from './PlatformAdminMonitoringPage';

const server = setupServer(...monitoringHandlers);

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
        <PlatformAdminMonitoringPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PlatformAdminMonitoringPage (통합 테스트)', () => {
  it('서버 상태 카드 3종을 렌더링한다', async () => {
    renderPage();

    const cards = within(await screen.findByTestId(SERVER_HEALTH_TEST_ID));
    expect(cards.getByText('API 서버')).toBeTruthy();
    expect(cards.getByText('AI 분석 서버')).toBeTruthy();
    expect(cards.getByText('DB')).toBeTruthy();
    expect(cards.getAllByText('정상').length).toBe(3);
    expect(cards.getByText('99.98%')).toBeTruthy();
  });

  it('분석 잡 큐 요약과 잡 목록을 렌더링한다', async () => {
    renderPage();

    const queue = within(await screen.findByTestId(JOB_QUEUE_TEST_ID));
    expect(queue.getByText('진행 2')).toBeTruthy();
    expect(queue.getByText('완료 148')).toBeTruthy();
    expect(queue.getByText('실패 1')).toBeTruthy();
    expect(queue.getByText('J-8892')).toBeTruthy();
    expect(queue.getByText('힐스테이트 광교 102동')).toBeTruthy();
  });

  it('최근 에러 로그는 최신 1일치(2023-10-24, 4건)만 렌더링한다', async () => {
    renderPage();

    const table = within(await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID));
    expect(table.getAllByText('ERROR').length).toBe(2);
    expect(table.getAllByText('WARN').length).toBe(2);
    expect(table.getByText('worker-queue')).toBeTruthy();
    // 전날(2023-10-23) 로그는 1일치 필터에 걸러져 보이지 않아야 한다
    expect(table.queryByText('daily-cron')).toBeNull();
  });

  it('"최근 에러 로그" 섹션에 전체 보기 어포던스를 노출한다', async () => {
    renderPage();

    await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID);
    expect(screen.getByText('전체 보기')).toBeTruthy();
  });

  it('조회 실패 시 에러 메시지를 노출한다', async () => {
    server.use(
      http.get('/api/platform-admin/monitoring', () =>
        HttpResponse.json(
          { success: false, data: null, error: { code: 'SERVER_ERROR', message: '서버 오류' } },
          { status: 500 },
        ),
      ),
    );
    renderPage();

    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});
