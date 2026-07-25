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
  // #848 — ServiceStatsKpiCards는 로딩·에러·성공 어느 상태든 testid 컨테이너 자체는 항상 렌더된다
  // (데이터 없어도 카드가 사라지지 않고 "-"로 자리를 지키는 설계). findByTestId는 로딩 중에도 즉시
  // resolve되므로, 그 뒤에 이어지는 값 검증은 반드시 실제 값이 표시될 때까지 find*로 기다려야 한다
  // — 그렇지 않으면 "-"가 남아있는 로딩 스냅샷에서 동기 단언이 실행되는 레이스가 생긴다.
  it('KPI 카드 4종을 렌더링한다', async () => {
    renderPage();

    const kpi = within(await screen.findByTestId(SERVICE_STATS_KPI_TEST_ID));
    expect(await kpi.findByText('1,284')).toBeTruthy();
    expect(kpi.getByText('152')).toBeTruthy();
    expect(kpi.getByText('24,180')).toBeTruthy();
    expect(kpi.getByText('486')).toBeTruthy();
  });

  it('플랜 분포와 상담 유형 분포를 렌더링한다', async () => {
    renderPage();

    // 실제 데이터가 도착한 시점을 기준으로 동기화한다(위 KPI 테스트와 동일 이유) — testid 컨테이너의
    // 마운트 시점이 아니라, 데이터 의존 텍스트 자체가 나타날 때까지 기다린다.
    expect(await screen.findByText('Free (60%)')).toBeTruthy();
    expect(screen.getByText('Standard (30%)')).toBeTruthy();
    expect(screen.getByText('Enterprise (10%)')).toBeTruthy();
    expect(screen.getByText('서비스 이용 방법')).toBeTruthy();
    expect(screen.getByText('312')).toBeTruthy();
  });

  it('월별 요약 표에 6개월치 행을 렌더링한다', async () => {
    renderPage();

    // <table>은 로딩 중에도 항상 렌더되므로(행만 "불러오는 중..."으로 대체) getByRole('table')
    // 자체는 동기화 지점이 못 된다 — 실제 행 데이터(월 텍스트)가 나타날 때까지 기다린다.
    // 라인·막대 차트의 X축 눈금도 "1월"~"6월" 텍스트를 그리므로 표 안으로 스코프한다.
    const table = within(screen.getByRole('table'));
    expect(await table.findByText('6월')).toBeTruthy();
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

    // isError가 되면 KPI뿐 아니라 가입자·분석요청 추이 차트도 각자 role="alert"를 렌더해 alert가
    // 여러 개가 된다(각 위젯이 자기 영역 에러를 스스로 안내하는 설계) — findByRole('alert') 단수
    // 매칭은 실패하므로, 페이지 상단 배너의 문구로 특정해 기다린다.
    expect(await screen.findByText('서비스 통계를 불러오지 못했습니다.', { exact: false })).toBeTruthy();
    const kpi = within(screen.getByTestId(SERVICE_STATS_KPI_TEST_ID));
    expect(kpi.getAllByText('-').length).toBeGreaterThan(0);
  });
});
