// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ReportDetailResponse } from '../api/reportApi';
import type { InspectionResponse, DefectDetailItem } from '../../inspection/api/inspectionApi.types';
import { ReportGenerateStubPage } from './ReportGenerateStubPage';

const mockInspection: InspectionResponse = {
  id: 1,
  facilityId: 1,
  createdBy: 1,
  assignedInspectorId: 1,
  roundNo: 1,
  inspectionDate: '2026-07-22',
  status: 'ANALYZED',
  createdAt: '2026-07-22T10:00:00Z',
};

const mockDefects: DefectDetailItem[] = [
  {
    id: 1,
    inspectionId: 1,
    type: '균열',
    grade: 'C',
    status: 'DETECTED',
    confidence: 0.98,
    isReviewed: false,
    bboxX: 0.12,
    bboxY: 0.3,
    bboxW: 0.18,
    bboxH: 0.08,
    crackWidthMm: 3.2,
    crackLengthMm: 45,
    createdAt: '2026-07-22T10:00:00Z',
  },
];

const mockFacility = {
  id: 1,
  name: '테스트 시설물',
  type: '건물',
  address: '서울시 강남구',
  builtYear: 2020,
  scale: 'SMALL',
  nextInspectionDueAt: '2026-08-22',
};

const mockReport: ReportDetailResponse = {
  id: 1,
  inspectionId: 1,
  version: 1,
  content: { summary: 'Test report' },
  status: 'DRAFT',
  createdBy: 1,
  createdAt: '2026-07-22T10:00:00Z',
};

let generateReportCallCount = 0;

const server = setupServer(
  http.get('/api/inspections/1', () => HttpResponse.json({ success: true, data: mockInspection })),
  http.get('/api/inspections/1/defects', () => HttpResponse.json({ success: true, data: mockDefects })),
  http.get('/api/facilities/1', () => HttpResponse.json({ success: true, data: mockFacility })),
  http.post('/api/inspections/1/reports', () => {
    generateReportCallCount += 1;
    return HttpResponse.json({ success: true, data: mockReport }, { status: 201 });
  }),
);

beforeAll(() => server.listen());
beforeEach(() => {
  generateReportCallCount = 0;
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('ReportGenerateStubPage', () => {
  const renderPage = () => {
    const queryClient = new QueryClient();
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/inspections/1/reports/generate']}>
          <Routes>
            <Route path="/inspections/:id/reports/generate" element={<ReportGenerateStubPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  };

  it('마운트 시점에는 초안 생성 API를 호출하지 않는다', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('보고서 초안 생성')).toBeTruthy();
    });

    expect(generateReportCallCount).toBe(0);
  });

  it('버튼 클릭 시 초안을 생성하고, 재마운트해도 다시 클릭하기 전에는 재호출하지 않는다', async () => {
    const { unmount } = renderPage();

    await waitFor(() => screen.getByText('보고서 초안 생성'));
    fireEvent.click(screen.getByRole('button', { name: '보고서 초안 생성' }));

    await waitFor(
      () => {
        expect(screen.getByText('보고서 생성 결과')).toBeTruthy();
      },
      { timeout: 3000 },
    );
    expect(generateReportCallCount).toBe(1);

    // 새로고침/재방문 시뮬레이션 — 재마운트만으로는 재호출되지 않아야 한다.
    unmount();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('보고서 초안 생성')).toBeTruthy();
    });
    expect(generateReportCallCount).toBe(1);
  });

  it('should handle invalid inspection ID gracefully', () => {
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/inspections/invalid/reports/generate']}>
          <Routes>
            <Route path="/inspections/:id/reports/generate" element={<ReportGenerateStubPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByText(/잘못된 접근/)).toBeTruthy();
  });
});
