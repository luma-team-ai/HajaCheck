// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ReportDetailResponse } from '../api/reportApi';
import type { InspectionResponse, DefectDetailItem, MediaResponse } from '../../inspection/api/inspectionApi.types';
import { isReportContent, type ReportContent } from '../types';
import { mockReportDetailResponse } from '../mocks/reportDetail.mock';
import { ReportGeneratePage } from './ReportGeneratePage';
import { buildReportPdfFileName, exportReportToPdf } from '../utils/exportReportToPdf';

vi.mock('../utils/exportReportToPdf', () => ({
  exportReportToPdf: vi.fn().mockResolvedValue(new Blob(['fake-pdf'])),
  buildReportPdfFileName: vi.fn().mockReturnValue('점검보고서_1_20260723.pdf'),
}));

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
    type: 'CRACK',
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

const mockContent: ReportContent = {
  overview: { purpose: '정기 점검', facility_summary: '테스트 시설물', scope: '전체' },
  summary: {
    overall_opinion: '양호',
    total_count: 1,
    count_by_grade: { A: 0, B: 0, C: 1, D: 0, E: 0 },
    key_findings: ['균열 발견'],
  },
  detail: {
    items: [
      { defect_type: '균열', location: '1층 벽체', severity_grade: 'C', description: '설명', cause: '원인' },
    ],
  },
  recommendation: {
    items: [
      { target: '1층 벽체', method: '보수', priority: '중', legal_basis: '관련 근거 없음', legal_basis_verified: false },
    ],
    monitoring_points: ['정기 재점검'],
  },
};

const mockReport: ReportDetailResponse = {
  ...mockReportDetailResponse,
  content: mockContent,
  groundingCheckPassed: null,
};

let generateReportCallCount = 0;
let reportState: ReportDetailResponse = mockReport;
let uploadedPdfFileName: string | null = null;
let uploadedPdfSize: number | null = null;
let finalizePdfUrl: string | null = null;

const server = setupServer(
  http.get('/api/inspections/1', () => HttpResponse.json({ success: true, data: mockInspection })),
  http.get('/api/inspections/1/defects', () => HttpResponse.json({ success: true, data: mockDefects })),
  http.get('/api/inspections/1/media', () => {
    const mockMedia: MediaResponse[] = [
      {
        id: 1,
        inspectionId: 1,
        fileType: 'IMAGE',
        thumbnailUrl: '/api/media/1/thumbnail',
        detailUrl: '/api/media/1/detail',
        mimeType: 'image/jpeg',
        capturedAt: '2026-07-22T10:00:00Z',
        gpsLat: null,
        gpsLng: null,
        createdAt: '2026-07-22T10:00:00Z',
      },
    ];
    return HttpResponse.json({ success: true, data: mockMedia });
  }),
  http.get('/api/facilities/1', () => HttpResponse.json({ success: true, data: mockFacility })),
  http.post('/api/inspections/1/reports', () => {
    generateReportCallCount += 1;
    reportState = mockReport;
    return HttpResponse.json({ success: true, data: reportState }, { status: 201 });
  }),
  http.get('/api/reports/1', () => HttpResponse.json({ success: true, data: reportState })),
  http.patch('/api/reports/1', async ({ request }) => {
    const body = (await request.json()) as { contentJson: string };
    reportState = { ...reportState, content: JSON.parse(body.contentJson), groundingCheckPassed: null };
    return HttpResponse.json({ success: true, data: reportState });
  }),
  http.post('/api/reports/1/grounding-recheck', () => {
    reportState = { ...reportState, groundingCheckPassed: true };
    return HttpResponse.json({ success: true, data: reportState });
  }),
  http.post('/api/reports/1/pdf', async ({ request }) => {
    const formData = await request.formData();
    const file = formData.get('file');
    uploadedPdfFileName =
      file && typeof file === 'object' && 'name' in file && typeof file.name === 'string'
        ? file.name
        : null;
    uploadedPdfSize =
      file && typeof file === 'object' && 'size' in file && typeof file.size === 'number'
        ? file.size
        : null;
    return HttpResponse.json({ success: true, data: { pdfUrl: '/api/reports/1/pdf/storage-key' } });
  }),
  http.post('/api/reports/1/finalize', async ({ request }) => {
    const body = (await request.json()) as { pdfUrl: string };
    finalizePdfUrl = body.pdfUrl;
    reportState = { ...reportState, status: 'FINALIZED', pdfUrl: body.pdfUrl };
    return HttpResponse.json({ success: true, data: reportState });
  }),
  http.get('/api/reports/1/pdf/storage-key', () =>
    new Response('fake-pdf-binary', {
      status: 200,
      headers: { 'Content-Type': 'application/pdf' },
    }),
  ),
);

beforeAll(() => server.listen());
beforeEach(() => {
  generateReportCallCount = 0;
  reportState = mockReport;
  uploadedPdfFileName = null;
  uploadedPdfSize = null;
  finalizePdfUrl = null;
  vi.mocked(exportReportToPdf).mockClear();
  vi.mocked(buildReportPdfFileName).mockClear();
});
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

describe('ReportGeneratePage', () => {
  const renderPage = () => {
    const queryClient = new QueryClient();
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reports/1']}>
          <Routes>
            <Route path="/reports/:reportId" element={<ReportGeneratePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  };

  it('마운트 시점에 reportId로 기존 보고서 상세를 불러온다', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('보고서 생성 결과')).toBeTruthy();
    });

    expect(generateReportCallCount).toBe(0);
  });

  it('should handle invalid report ID gracefully', () => {
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reports/invalid']}>
          <Routes>
            <Route path="/reports/:reportId" element={<ReportGeneratePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByText(/잘못된 접근/)).toBeTruthy();
  });

  it('편집 → 저장 → 확정 검증 → PDF 생성 후 확정 순으로 진행하면 최종 FINALIZED로 전환된다', async () => {
    renderPage();

    await screen.findByText('보고서 생성 결과');

    const saveButton = screen.getByRole('button', { name: '저장' }) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);

    const purposeInput = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    fireEvent.change(purposeInput, { target: { value: '수정된 목적' } });
    expect(saveButton.disabled).toBe(false);

    fireEvent.click(saveButton);
    await waitFor(() => expect(saveButton.disabled).toBe(true));

    const recheckButton = screen.getByRole('button', { name: '확정 검증' }) as HTMLButtonElement;
    expect(recheckButton.disabled).toBe(false);
    fireEvent.click(recheckButton);

    await waitFor(() => {
      expect(screen.getByText('✓ 검증 완료')).toBeTruthy();
    });

    const finalizeButton = screen.getByRole('button', { name: 'PDF 생성 후 확정' }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    await waitFor(() => {
      expect(screen.getByText('이 보고서는 확정되어 더 이상 편집할 수 없습니다.')).toBeTruthy();
    });

    expect(exportReportToPdf).toHaveBeenCalledWith(expect.objectContaining({
      overview: expect.objectContaining({ purpose: '수정된 목적' }),
    }));
    expect(buildReportPdfFileName).toHaveBeenCalledWith(1);
    expect(uploadedPdfFileName).toBeTruthy();
    expect(uploadedPdfSize).toBeGreaterThan(0);
    expect(finalizePdfUrl).toBe('/api/reports/1/pdf/storage-key');
    expect(screen.getByRole('link', { name: 'PDF 보기' }).getAttribute('href')).toBe('/reports/1?mode=export');
    expect((screen.getByLabelText('점검 목적') as HTMLTextAreaElement).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull();
  });

  it('기존 reportId 상세 content로 진입해 바로 PDF 생성 후 확정할 수 있다', async () => {
    if (!isReportContent(mockReportDetailResponse.content)) {
      throw new Error('report detail fixture content must match ReportContent');
    }
    const realContractContent = mockReportDetailResponse.content;

    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
    };

    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reports/1']}>
          <Routes>
            <Route path="/reports/:reportId" element={<ReportGeneratePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByDisplayValue(realContractContent.overview.purpose);

    const finalizeButton = screen.getByRole('button', { name: 'PDF 생성 후 확정' }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    await waitFor(() => {
      expect(screen.getByText('이 보고서는 확정되어 더 이상 편집할 수 없습니다.')).toBeTruthy();
    });
    expect(exportReportToPdf).toHaveBeenCalledWith(realContractContent);
    expect(buildReportPdfFileName).toHaveBeenCalledWith(1);
    expect(uploadedPdfFileName).toBeTruthy();
    expect(uploadedPdfSize).toBeGreaterThan(0);
    expect(finalizePdfUrl).toBe('/api/reports/1/pdf/storage-key');
  });

  it('/reports/:reportId?mode=export에서 저장된 실제 PDF를 iframe으로 렌더한다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: '/api/reports/1/pdf/storage-key',
    };

    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reports/1?mode=export']}>
          <Routes>
            <Route path="/reports/:reportId" element={<ReportGeneratePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const pdfFrame = await screen.findByTitle('저장된 보고서 PDF');
    expect(pdfFrame.getAttribute('src')).toMatch(/^blob:/);
    expect(pdfFrame.getAttribute('src')).toContain('toolbar=0');
    expect(pdfFrame.getAttribute('src')).toContain('navpanes=0');
    expect(pdfFrame.className).toContain('border-0');
    expect(screen.queryByLabelText('점검 목적')).toBeNull();
  });

  it('/reports/:reportId?mode=export에서 pdfUrl이 없으면 코드 미리보기 대신 저장된 PDF 없음 상태를 보여준다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: null,
    };

    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reports/1?mode=export']}>
          <Routes>
            <Route path="/reports/:reportId" element={<ReportGeneratePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('저장된 PDF가 없습니다.')).toBeTruthy();
    expect(screen.queryByTitle('저장된 보고서 PDF')).toBeNull();
    expect(screen.queryByLabelText('점검 목적')).toBeNull();
  });

  it('content가 편집되지 않은 상태에서는 확정 검증 버튼이 항상 비활성화되지 않는다', async () => {
    renderPage();

    await screen.findByText('보고서 생성 결과');

    const recheckButton = screen.getByRole('button', { name: '확정 검증' }) as HTMLButtonElement;
    expect(recheckButton.disabled).toBe(false);

    const finalizeButton = screen.getByRole('button', { name: 'PDF 생성 후 확정' }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(true);
  });

  it('저장 실패 시 axios 인터셉터가 던진 ApiError의 실제 message를 그대로 노출한다(제네릭 문구로 덮지 않는다)', async () => {
    server.use(
      http.patch('/api/reports/1', () =>
        HttpResponse.json(
          { success: false, error: { code: 'REPORT_ALREADY_FINALIZED', message: '이미 확정된 보고서는 수정할 수 없습니다.' } },
          { status: 400 },
        ),
      ),
    );

    renderPage();

    await screen.findByText('보고서 생성 결과');

    const purposeInput = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    fireEvent.change(purposeInput, { target: { value: '수정된 목적' } });

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(screen.getByText('이미 확정된 보고서는 수정할 수 없습니다.')).toBeTruthy();
    });
    expect(screen.queryByText('저장에 실패했습니다.')).toBeNull();
  });

  it('/reports/:reportId 경로의 reportId로 getReport를 호출하여 기존 보고서를 로드한다', async () => {
    let getReportCalled = false;
    server.use(
      http.get('/api/reports/99', () => {
        getReportCalled = true;
        return HttpResponse.json({ success: true, data: { ...mockReport, id: 99 } });
      }),
    );

    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/reports/99']}>
          <Routes>
            <Route path="/reports/:reportId" element={<ReportGeneratePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('보고서 생성 결과')).toBeTruthy();
    });
    expect(getReportCalled).toBe(true);
  });
});
