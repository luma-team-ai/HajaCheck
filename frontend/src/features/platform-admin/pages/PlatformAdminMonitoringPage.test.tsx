// @vitest-environment jsdom
// PlatformAdminMonitoringPage 통합 테스트 — Figma node-id 1-404 기준(#728로 HF API 사용량 카드는
// 서버 자원 카드로 대체됨).
// 실제 useSystemMonitoring 훅 + MSW monitoringHandlers를 통해 서버 상태·잡 큐·서버 자원·에러
// 로그 렌더와 에러 상태를 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { monitoringHandlers } from '../api/monitoringApi.handlers';
import { JOB_QUEUE_TEST_ID } from '../components/AnalysisJobQueueCard';
import { ERROR_LOG_TABLE_TEST_ID } from '../components/ErrorLogTable';
import { SERVER_HEALTH_TEST_ID } from '../components/ServerHealthCards';
import { SERVER_RESOURCE_TEST_ID } from '../components/ServerResourceCard';
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

  it('서버 자원 카드를 렌더링한다', async () => {
    renderPage();

    const card = within(await screen.findByTestId(SERVER_RESOURCE_TEST_ID));
    expect(card.getByText('CPU')).toBeTruthy();
    expect(card.getByText('메모리(JVM 힙)')).toBeTruthy();
    expect(card.getByText('디스크')).toBeTruthy();
    expect(card.getByText('42.5%')).toBeTruthy();
  });

  it('날짜 검색 입력값은 오늘 날짜로 기본 설정된다', async () => {
    renderPage();

    await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID);
    const today = new Date();
    const expected = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(
      today.getDate(),
    ).padStart(2, '0')}`;
    expect(screen.getByLabelText<HTMLInputElement>('날짜 검색').value).toBe(expected);
  });

  it('날짜 검색을 비우면 날짜 제한 없이 전체 목록을 렌더링한다', async () => {
    renderPage();

    await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID);
    fireEvent.change(screen.getByLabelText('날짜 검색'), { target: { value: '' } });

    const table = within(screen.getByTestId(ERROR_LOG_TABLE_TEST_ID));
    expect(table.getAllByText('ERROR').length).toBe(2);
    expect(table.getAllByText('WARN').length).toBe(3);
    expect(table.getByText('worker-queue')).toBeTruthy();
    // 전날(2023-10-23) 로그도 날짜 제한 없이 노출돼야 한다
    expect(table.getByText('daily-cron')).toBeTruthy();
  });

  it('ERROR/WARN 라벨을 클릭하면 해당 레벨만 조회된다', async () => {
    renderPage();

    await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID);
    fireEvent.change(screen.getByLabelText('날짜 검색'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'WARN' }));

    const table = within(screen.getByTestId(ERROR_LOG_TABLE_TEST_ID));
    expect(table.getAllByText('WARN').length).toBe(3);
    expect(table.queryByText('ERROR')).toBeNull();
    expect(table.getByText('daily-cron')).toBeTruthy();
  });

  it('날짜를 검색하면 해당 날짜의 로그만 조회된다', async () => {
    renderPage();

    await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID);
    fireEvent.change(screen.getByLabelText('날짜 검색'), { target: { value: '2023-10-24' } });

    const table = within(screen.getByTestId(ERROR_LOG_TABLE_TEST_ID));
    expect(table.getByText('worker-queue')).toBeTruthy();
    expect(table.queryByText('daily-cron')).toBeNull();
  });

  it('"에러 로그" 섹션에 페이지네이션 컨트롤을 노출한다', async () => {
    renderPage();

    await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID);
    expect(screen.getByRole('navigation', { name: '페이지 네비게이션' })).toBeTruthy();
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
