// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { facilityComparisonHandlers } from '../api/facilityComparisonApi.handlers';
import { FacilityInspectionComparePage } from './FacilityInspectionComparePage';

// html2canvas는 실 canvas 렌더링에 의존해 jsdom에서 재현 불가능한 브라우저 API 경계라
// export util을 모듈 경계에서 모킹한다(react-testing.md "네트워크/외부 경계 모킹" 관용구).
const exportComparisonReportAsPngMock = vi.fn().mockResolvedValue(undefined);
vi.mock('../utils/exportComparisonReportAsPng', () => ({
  exportComparisonReportAsPng: (...args: unknown[]) => exportComparisonReportAsPngMock(...args),
}));

const server = setupServer(...facilityComparisonHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  exportComparisonReportAsPngMock.mockClear();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/facilities/1/compare']}>
        <Routes>
          <Route path="/facilities/:id/compare" element={<FacilityInspectionComparePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FacilityInspectionComparePage (통합 테스트)', () => {
  it('제목과 회차 선택 드롭다운을 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('회차 간 비교')).not.toBeNull();
    expect(screen.getByLabelText('이전 회차')).not.toBeNull();
    expect(screen.getByLabelText('현재 회차')).not.toBeNull();
  });

  it('KPI 카드 4개를 값과 함께 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('신규 하자')).not.toBeNull();
    expect(screen.getByText('14')).not.toBeNull();
    expect(screen.getByText('진행성 (악화)')).not.toBeNull();
    expect(screen.getByText('개선/조치 완료')).not.toBeNull();
    expect(screen.getByText('등급 상승')).not.toBeNull();
  });

  it('시각적 비교 패널과 진행성 균열 추이 차트를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('시각적 비교')).not.toBeNull();
    expect(screen.getByText('동일 촬영 지점 정렬됨', { exact: false })).not.toBeNull();
    expect(screen.getByText('진행성 균열 추이')).not.toBeNull();
    expect(screen.getByRole('img', { name: /균열 폭 추이/ })).not.toBeNull();
  });

  it('하자 변화 목록 테이블에 위치·변화 배지를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('하자 변화 목록')).not.toBeNull();
    expect(screen.getByText('외벽 A구간 / 균열')).not.toBeNull();
    expect(screen.getByText('악화')).not.toBeNull();
    expect(screen.getByText('신규')).not.toBeNull();
    expect(screen.getByText('유지')).not.toBeNull();
    expect(screen.getByText('조치완료')).not.toBeNull();
  });

  it('"내보내기" 클릭 시 메인 콘텐츠 영역을 대상으로 PNG 내보내기를 호출한다', async () => {
    renderPage();
    await screen.findByText('회차 간 비교');

    fireEvent.click(screen.getByRole('button', { name: '내보내기' }));

    expect(exportComparisonReportAsPngMock).toHaveBeenCalledTimes(1);
    expect(exportComparisonReportAsPngMock.mock.calls[0][1]).toBe('1');
  });
});