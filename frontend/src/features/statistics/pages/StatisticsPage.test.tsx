// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { statisticsHandlers } from '../api/statisticsApi.handlers';
import { StatisticsPage } from './StatisticsPage';

// exportStatisticsAsPdf는 실제로 폰트를 fetch하고 jsPDF/jspdf-autotable로 문서를 조립하는
// 무거운 비동기 작업이라(#1692, 화면 캡처가 아니라 표 문서 조립으로 전환), 페이지 통합 테스트
// 레벨에서는 모듈 경계에서 모킹하고 "어떤 데이터로 호출됐는지"만 검증한다
// (FacilityInspectionComparePage.test.tsx와 동일 관용구 — 조립 로직 자체는
// exportStatisticsAsPdf.test.ts에서 별도로 검증한다).
const exportStatisticsAsPdfMock = vi.fn().mockResolvedValue(undefined);
vi.mock('../utils/exportStatisticsAsPdf', () => ({
  exportStatisticsAsPdf: (...args: unknown[]) => exportStatisticsAsPdfMock(...args),
}));

const server = setupServer(...statisticsHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  exportStatisticsAsPdfMock.mockClear();
  exportStatisticsAsPdfMock.mockResolvedValue(undefined);
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <StatisticsPage />
    </QueryClientProvider>,
  );
}

describe('StatisticsPage — 내보내기 (#1692, CSV→화면 캡처→표 문서 최종 전환)', () => {
  it('"내보내기" 클릭 시 현재 조회 조건과 이미 로드된 통계 데이터를 exportStatisticsAsPdf에 넘긴다', async () => {
    renderPage();
    await screen.findByText('통계');
    // KPI 쿼리가 실제로 로드될 때까지 기다린다 — 클릭 시점에 아직 데이터가 없으면
    // kpiSummary가 undefined로 넘어가 이 검증이 무의미해진다.
    await screen.findByText('1,842');

    fireEvent.click(screen.getByRole('button', { name: '통계 데이터 내보내기' }));

    expect(exportStatisticsAsPdfMock).toHaveBeenCalledTimes(1);
    const params = exportStatisticsAsPdfMock.mock.calls[0][0];
    expect(params.periodLabel).toBe('최근 6개월');
    expect(params.facilityLabel).toBe('전체 시설물');
    expect(params.kpiSummary?.totalDefects).toBe(1842);
  });

  it('내보내는 중에는 버튼이 비활성화되고 완료되면 다시 활성화된다', async () => {
    let resolveExport: () => void = () => {};
    exportStatisticsAsPdfMock.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveExport = resolve;
        }),
    );

    renderPage();
    await screen.findByText('통계');

    const exportButton = screen.getByRole('button', { name: '통계 데이터 내보내기' });
    fireEvent.click(exportButton);

    expect(await screen.findByText('내보내는 중...')).not.toBeNull();
    expect(exportButton).toHaveProperty('disabled', true);

    resolveExport();

    // StatisticsFilterBar는 클릭 시점에 자체적으로 "완료!" 표시를 예약해두므로(2초 타이머),
    // 부모의 isExporting이 false로 풀리면 "내보내는 중..."에서 "완료!"로 바뀐다.
    await screen.findByText('완료!');
    expect(exportButton).toHaveProperty('disabled', false);
  });

  it('내보내기 실패 시 에러 문구를 노출한다', async () => {
    exportStatisticsAsPdfMock.mockRejectedValueOnce(new Error('capture failed'));

    renderPage();
    await screen.findByText('통계');

    fireEvent.click(screen.getByRole('button', { name: '통계 데이터 내보내기' }));

    expect(await screen.findByRole('alert')).toHaveProperty(
      'textContent',
      '내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.',
    );
  });
});
