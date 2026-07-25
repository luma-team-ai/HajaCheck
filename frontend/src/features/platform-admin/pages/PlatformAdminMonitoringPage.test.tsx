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
  // #848 — 이 페이지의 카드·테이블 컨테이너(각 TEST_ID)는 로딩·에러·성공 어느 상태든 항상 렌더된다
  // (ServerHealthCards는 로딩 중 items=[]로 빈 grid를, ServerResourceCard/테이블류는 "불러오는
  // 중..." 상태 노드를 그 자리에 둔다). findByTestId는 그 즉시(로딩 중에도) resolve되므로, 뒤이은
  // 값 검증은 실제 데이터 텍스트가 나타날 때까지 find*로 기다려야 한다 — 그렇지 않으면 데이터 도착
  // 전 스냅샷에서 동기 단언이 실행되는 레이스가 생긴다.
  it('서버 상태 카드 3종을 렌더링한다', async () => {
    renderPage();

    const cards = within(await screen.findByTestId(SERVER_HEALTH_TEST_ID));
    expect(await cards.findByText('API 서버')).toBeTruthy();
    expect(cards.getByText('AI 분석 서버')).toBeTruthy();
    expect(cards.getByText('DB')).toBeTruthy();
    expect(cards.getAllByText('정상').length).toBe(3);
    expect(cards.getByText('99.98%')).toBeTruthy();
  });

  it('분석 잡 큐 요약과 잡 목록을 렌더링한다', async () => {
    renderPage();

    const queue = within(await screen.findByTestId(JOB_QUEUE_TEST_ID));
    expect(await queue.findByText('진행 2')).toBeTruthy();
    expect(queue.getByText('완료 148')).toBeTruthy();
    expect(queue.getByText('실패 1')).toBeTruthy();
    expect(queue.getByText('J-8892')).toBeTruthy();
    expect(queue.getByText('힐스테이트 광교 102동')).toBeTruthy();
  });

  it('서버 자원 카드를 렌더링한다', async () => {
    renderPage();

    const card = within(await screen.findByTestId(SERVER_RESOURCE_TEST_ID));
    expect(await card.findByText('CPU')).toBeTruthy();
    expect(card.getByText('메모리(JVM 힙)')).toBeTruthy();
    expect(card.getByText('디스크')).toBeTruthy();
    expect(card.getByText('42.5%')).toBeTruthy();
  });

  it('날짜 검색 기본값은 비어 있어(전체) 초기 렌더에서 바로 전체 목록을 보여준다', async () => {
    renderPage();

    const table = within(await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID));
    expect(screen.getByLabelText<HTMLInputElement>('날짜 검색').value).toBe('');
    expect(await table.findByText('worker-queue')).toBeTruthy();
    expect(table.getAllByText('ERROR').length).toBe(2);
    expect(table.getAllByText('WARN').length).toBe(3);
    // 전날(2023-10-23) 로그도 기본 화면에서 바로 노출돼야 한다(과거 '오늘' 기본값 회귀 방지)
    expect(table.getByText('daily-cron')).toBeTruthy();
  });

  it('ERROR/WARN 라벨을 클릭하면 해당 레벨만 조회된다', async () => {
    renderPage();

    await screen.findByTestId(ERROR_LOG_TABLE_TEST_ID);
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

    // isError가 되면 페이지 상단 배너뿐 아니라 ServerResourceCard·ErrorLogTable도 각자
    // role="alert"를 렌더해(각 위젯이 자기 영역 에러를 스스로 안내하는 설계) alert가 여러 개가
    // 된다 — findByRole('alert') 단수 매칭은 실패하므로, 페이지 상단 배너 문구로 특정해 기다린다.
    expect(await screen.findByText('시스템 모니터링 정보를 불러오지 못했습니다.', { exact: false })).toBeTruthy();
  });
});
