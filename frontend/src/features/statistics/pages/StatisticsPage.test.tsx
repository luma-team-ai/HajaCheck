// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { statisticsHandlers } from '../api/statisticsApi.handlers';
import { StatisticsPage } from './StatisticsPage';

// html2canvas는 실 canvas 렌더링에 의존해 jsdom에서 재현 불가능한 브라우저 API 경계라
// export util을 모듈 경계에서 모킹한다(FacilityInspectionComparePage.test.tsx와 동일 관용구).
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

describe('StatisticsPage — 내보내기 (#1692, CSV→PDF 전환)', () => {
  it('"내보내기" 클릭 시 통계 카드 전체 영역을 대상으로 PDF 내보내기를 호출한다', async () => {
    renderPage();
    await screen.findByText('통계');

    fireEvent.click(screen.getByRole('button', { name: '통계 데이터 내보내기' }));

    expect(exportStatisticsAsPdfMock).toHaveBeenCalledTimes(1);
    const capturedNode = exportStatisticsAsPdfMock.mock.calls[0][0] as HTMLElement;
    // 캡처 대상은 제목 "통계" + 필터바를 포함한 카드 전체 div여야 한다(조회 조건이 PDF에 남아야 함).
    expect(capturedNode.textContent).toContain('통계');
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
