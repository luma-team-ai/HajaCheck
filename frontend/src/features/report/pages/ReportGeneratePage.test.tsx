// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, RouterProvider, createMemoryRouter } from 'react-router-dom';
import type { ReportDetailResponse } from '../api/reportApi';
import type { InspectionResponse, DefectDetailItem, MediaResponse } from '../../inspection/api/inspectionApi.types';
import { isReportContent, type ReportContent } from '../types';
import { AI_DRAFT_WARNING, AI_DRAFT_WARNING_TITLE } from '../constants';
import { mockReportDetailResponse } from '../mocks/reportDetail.mock';
import { ReportGeneratePage } from './ReportGeneratePage';
import { buildReportPdfFileName, exportReportToPdf } from '../utils/exportReportToPdf';
import { DetailSection } from '../components/editor/DetailSection';

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
  http.head('/api/reports/1/pdf/storage-key', () =>
    new Response(null, {
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
  const renderPageWithPath = (path: string) => {
    const queryClient = new QueryClient();
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/reports/:reportId" element={<ReportGeneratePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  };

  const renderPage = () => renderPageWithPath('/reports/1');

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

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    await waitFor(() => expect(finalizeButton.disabled).toBe(false));
    expect(screen.queryByText('✓ 검증 완료')).toBeNull();
    fireEvent.click(finalizeButton);

    await waitFor(() => {
      expect(screen.getByText('이 보고서는 확정되어 더 이상 편집할 수 없습니다.')).toBeTruthy();
    });

    expect(exportReportToPdf).toHaveBeenCalledWith(
      expect.objectContaining({ overview: expect.objectContaining({ purpose: '수정된 목적' }) }),
      expect.objectContaining({ facilityName: '테스트 시설물', inspectionRound: 1 }),
    );
    expect(buildReportPdfFileName).toHaveBeenCalledWith(1);
    expect(uploadedPdfFileName).toBeTruthy();
    expect(uploadedPdfSize).toBeGreaterThan(0);
    expect(finalizePdfUrl).toBe('/api/reports/1/pdf/storage-key');
    expect(screen.getByRole('link', { name: 'PDF 보기' }).getAttribute('href')).toBe('/reports/1?mode=export');
    const purposeTextarea = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    expect(purposeTextarea.readOnly).toBe(true);
    expect(purposeTextarea.disabled).toBe(false);
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull();
  });

  it('저장 요청 중에는 편집 입력을 잠가 진행 중 응답이 최신 입력을 덮어쓰지 않게 한다', async () => {
    let resolveSave: (() => void) | undefined;
    server.use(
      http.patch('/api/reports/1', async ({ request }) => {
        const body = (await request.json()) as { contentJson: string };
        await new Promise<void>((resolve) => {
          resolveSave = resolve;
        });
        reportState = { ...reportState, content: JSON.parse(body.contentJson) };
        return HttpResponse.json({ success: true, data: reportState });
      }),
    );

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const purposeInput = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    fireEvent.change(purposeInput, { target: { value: '저장 중 변경 방지' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(purposeInput.readOnly).toBe(true));
    resolveSave?.();
    await waitFor(() => expect(purposeInput.readOnly).toBe(false));
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

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    await waitFor(() => {
      expect(screen.getByText('이 보고서는 확정되어 더 이상 편집할 수 없습니다.')).toBeTruthy();
    });
    expect(exportReportToPdf).toHaveBeenCalledWith(
      realContractContent,
      expect.objectContaining({ facilityName: '테스트 시설물', inspectionRound: 1 }),
    );
    expect(buildReportPdfFileName).toHaveBeenCalledWith(1);
    expect(uploadedPdfFileName).toBeTruthy();
    expect(uploadedPdfSize).toBeGreaterThan(0);
    expect(finalizePdfUrl).toBe('/api/reports/1/pdf/storage-key');
  });

  it('대표 사진 제외 옵션이면 PDF 생성 컨텍스트에 하자 이미지를 넣지 않는다', async () => {
    server.use(
      http.get('/api/inspections/1/defects', () =>
        HttpResponse.json({
          success: true,
          data: [{ ...mockDefects[0], thumbnailUrl: '/api/media/1/thumbnail' }],
        }),
      ),
    );
    reportState = {
      ...mockReport,
      groundingCheckPassed: true,
      content: { ...mockContent, reportOptions: { sections: ['overview'], includePhoto: false } },
    };

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    await waitFor(() => {
      expect(exportReportToPdf).toHaveBeenCalledWith(
        expect.objectContaining({ reportOptions: expect.objectContaining({ includePhoto: false }) }),
        expect.objectContaining({ defectImages: [] }),
      );
    });
  });

  it('/reports/:reportId?mode=export에서 저장된 실제 PDF를 iframe으로 렌더한다', async () => {
    let preflightCount = 0;
    server.use(
      http.get('/api/reports/1/pdf/storage-key', () => {
        preflightCount += 1;
        return new Response('fake-pdf-binary', {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }),
    );
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
    expect(preflightCount).toBe(1);
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(pdfFrame.getAttribute('src')).toContain('toolbar=0');
    expect(pdfFrame.getAttribute('src')).toContain('navpanes=0');
    expect(pdfFrame.getAttribute('src')).toContain('view=FitH');
    expect(pdfFrame.className).toContain('border-0');
    expect(screen.queryByLabelText('점검 목적')).toBeNull();
  });

  it('localhost 절대 pdfUrl은 현재 origin의 /api 경로로 정규화해 iframe으로 연다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: 'http://localhost:8080/api/reports/1/pdf/storage-key',
    };

    renderPageWithPath('/reports/1?mode=export');

    const pdfFrame = await screen.findByTitle('저장된 보고서 PDF');
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(pdfFrame.getAttribute('src')).not.toContain('localhost:8080');
  });

  it('내부 호스트 pdfUrl도 현재 origin의 /api 경로로 정규화해 iframe으로 연다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: 'http://spring:8080/api/reports/1/pdf/storage-key',
    };

    renderPageWithPath('/reports/1?mode=export');

    const pdfFrame = await screen.findByTitle('저장된 보고서 PDF');
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(pdfFrame.getAttribute('src')).not.toContain('spring:8080');
  });

  it('same-origin PDF 사전 확인 실패 시 현재 보고서 내용으로 PDF 미리보기를 다시 렌더한다', async () => {
    server.use(
      http.get('/api/reports/1/pdf/storage-key', () =>
        new Response(null, { status: 403 }),
      ),
    );
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: '/api/reports/1/pdf/storage-key',
    };

    renderPageWithPath('/reports/1?mode=export');

    const pdfFrame = await screen.findByTitle('보고서 PDF 미리보기(확정 전)');
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(screen.getByText(/저장된 PDF를 찾지 못해 현재 보고서 내용으로 다시 렌더링했습니다/)).toBeTruthy();
    expect(exportReportToPdf).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'PDF 내보내기' })).toBeTruthy();
    expect(screen.queryByTitle('저장된 보고서 PDF')).toBeNull();
    expect(screen.queryByText('PDF를 불러올 수 없습니다.')).toBeNull();
  });

  it('cross-origin pdfUrl은 사전 fetch 없이 iframe이 직접 열게 한다', async () => {
    let requestCount = 0;
    server.use(
      http.get('https://cdn.example.test/reports/1.pdf', () => {
        requestCount += 1;
        return new Response('fake-pdf-binary', {
          status: 200,
          headers: { 'Content-Type': 'application/pdf', 'Access-Control-Allow-Origin': '*' },
        });
      }),
    );
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: 'https://cdn.example.test/reports/1.pdf',
    };

    renderPageWithPath('/reports/1?mode=export');

    const pdfFrame = await screen.findByTitle('저장된 보고서 PDF');
    expect(pdfFrame.getAttribute('src')).toContain('https://cdn.example.test/reports/1.pdf#');
    expect(requestCount).toBe(0);
  });

  // 회귀 테스트(#1235 P2) — 언마운트 없이(같은 라우트 패턴, reportId만 바뀌는 클라이언트 라우팅)
  // same-origin 프리플라이트 대상 리포트에서 프리플라이트 비대상(cross-origin) 리포트로 전환하면,
  // verifyPdfPreview가 pdfBlobUrl을 건드리지 않고 조기 종료해 이전 리포트의 blob이 새 화면에
  // 그대로 남아있던 문제를 검증한다.
  it('#1235 P2: 리포트 전환 시 이전 리포트의 blob URL을 재사용하지 않는다', async () => {
    const report2: ReportDetailResponse = {
      ...mockReportDetailResponse,
      id: 2,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: 'https://cdn.example.test/report2.pdf',
    };
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'FINALIZED',
      pdfUrl: '/api/reports/1/pdf/storage-key',
    };

    server.use(
      http.get('/api/reports/1/pdf/storage-key', () =>
        new Response('fake-pdf-binary', {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        }),
      ),
      http.get('/api/reports/1', () => HttpResponse.json({ success: true, data: reportState })),
      http.get('/api/reports/2', () => HttpResponse.json({ success: true, data: report2 })),
      http.get('https://cdn.example.test/report2.pdf', () =>
        new Response('fake-pdf-binary-2', {
          status: 200,
          headers: { 'Content-Type': 'application/pdf', 'Access-Control-Allow-Origin': '*' },
        }),
      ),
    );

    const queryClient = new QueryClient();
    const router = createMemoryRouter(
      [{ path: '/reports/:reportId', element: <ReportGeneratePage /> }],
      { initialEntries: ['/reports/1?mode=export'] },
    );
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    const firstFrame = await screen.findByTitle('저장된 보고서 PDF');
    expect(firstFrame.getAttribute('src')).toContain('blob:');

    await router.navigate('/reports/2?mode=export');

    // pdfPreviewKey가 바뀌면 iframe이 새 DOM 노드로 리마운트되므로, 특정 노드를 미리 붙잡지 않고
    // waitFor 콜백마다 현재 DOM을 다시 조회해 최종 정착 상태를 확인한다.
    await waitFor(() => {
      const frame = screen.getByTitle('저장된 보고서 PDF');
      expect(frame.getAttribute('src')).toContain('https://cdn.example.test/report2.pdf');
      expect(frame.getAttribute('src')).not.toContain('blob:');
    });
  });

  it('/reports/:reportId?mode=export에서 확정 보고서의 pdfUrl이 없어도 현재 내용으로 PDF 미리보기를 렌더한다', async () => {
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

    const pdfFrame = await screen.findByTitle('보고서 PDF 미리보기(확정 전)');
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(screen.queryByTitle('저장된 보고서 PDF')).toBeNull();
    expect(screen.queryByText('저장된 PDF가 없습니다.')).toBeNull();
    expect(screen.queryByLabelText('점검 목적')).toBeNull();
  });

  // 회귀 테스트(사용자 리포트 픽스) — 확정 검증을 통과했는데도 아직 확정(PDF 업로드)하지 않은
  // 상태에서 "PDF 미리보기"에 들어가면, 서버에 저장된 PDF가 없어도 클라이언트에서 즉석
  // 렌더링한 미리보기가 떠야 한다("확정하기 전 PDF를 보는 기능"의 본래 의도).
  it('/reports/:reportId?mode=export에서 확정 전이어도 검증을 통과했으면 즉석 미리보기를 렌더한다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: true,
      status: 'DRAFT',
      pdfUrl: null,
    };

    renderPageWithPath('/reports/1?mode=export');

    const pdfFrame = await screen.findByTitle('보고서 PDF 미리보기(확정 전)');
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(screen.getByText(/아직 확정되지 않은 미리보기입니다/)).toBeTruthy();
    expect(screen.queryByText('저장된 PDF가 없습니다.')).toBeNull();
  });

  // 검증 전(그리고 grounding이라는 단어를 노출하지 않는지) 확인 — 일반 점검자가 이해할 수 있는
  // 문구여야 한다.
  it('확정 검증을 아직 통과하지 못했으면 미리보기 대신 안내 문구를 plain하게 보여준다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: null,
      status: 'DRAFT',
      pdfUrl: null,
    };

    renderPageWithPath('/reports/1?mode=export');

    const guidance = await screen.findByText('아직 미리 볼 수 없습니다.');
    expect(guidance).toBeTruthy();
    expect(screen.getByText(/확정 검증을 통과한 뒤 다시 시도해 주세요/)).toBeTruthy();
    expect(screen.queryByText(/grounding/i)).toBeNull();
    expect(screen.queryByTitle('보고서 PDF 미리보기(확정 전)')).toBeNull();
  });

  it('content가 편집되지 않은 상태에서는 확정 검증 버튼이 항상 비활성화되지 않는다', async () => {
    renderPage();

    await screen.findByText('보고서 생성 결과');

    const recheckButton = screen.getByRole('button', { name: '확정 검증' }) as HTMLButtonElement;
    expect(recheckButton.disabled).toBe(false);

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'PDF 생성 후 확정' })).toBeNull();
  });

  it('서식 섹션 추가 메뉴에 표준서식 수동 입력 항목을 모두 노출한다', async () => {
    renderPage();

    await screen.findByText('보고서 생성 결과');
    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));

    expect(screen.getByRole('button', { name: '제출문' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '기본현황' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '점검 결과 및 보수보강' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '책임/참여기술자' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '결과요약/종합의견' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '부위별 상태평가/보수보강' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '안전성 평가' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '현장시험' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '시설물 현황' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '위치도/전경/도면/부위별 사진' })).toBeTruthy();
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

  // --- #1095 Figma 시안 재설계 테스트 ---
  it('페이지 내부 breadcrumb를 다시 그리지 않고 공용 Header breadcrumb에 위임한다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByRole('heading', { name: '보고서 생성 결과' })).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: '상단 경로' })).toBeNull();
  });

  it('통계 카드 4개(현재 상태/생성일시/검수 완료율/총 지적 수)가 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByText('현재 상태')).toBeTruthy();
    expect(screen.getByText('생성일시')).toBeTruthy();
    expect(screen.getByText('검수 완료율')).toBeTruthy();
    expect(screen.getByText('총 지적 수')).toBeTruthy();
  });

  it('단계 표시 A→C(AI 분류/작성자 확인/발행)가 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByText('AI 분류')).toBeTruthy();
    expect(screen.getByText('작성자 확인')).toBeTruthy();
    expect(screen.getByText('발행')).toBeTruthy();
    expect(screen.queryByText('초안 생성')).toBeNull();
    expect(screen.queryByText('엔지니어 확인')).toBeNull();
    expect(screen.queryByText('최종 승인')).toBeNull();
  });

  it('상세 내역 등급 필터 pills(전체, A, B, C, D, E)가 항상 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    const filterGroup = screen.getByRole('group', { name: '등급 필터' });
    expect(filterGroup).toBeTruthy();
    for (const g of ['전체', 'A', 'B', 'C', 'D', 'E']) {
      expect(within(filterGroup).getByRole('button', { name: new RegExp(`^${g}`) })).toBeTruthy();
    }
  });

  it('상세 내역 페이지네이션 컨트롤이 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeTruthy();
    const detailSection = screen.getByRole('heading', { name: '상세 내역' }).closest('section');
    expect(detailSection).toBeTruthy();
    expect(within(detailSection!).getByText('1', { selector: 'span.font-bold' })).toBeTruthy();
    expect(within(detailSection!).getByText('/ 1', { selector: 'span.text-zinc-500' })).toBeTruthy();
  });

  it('조치 권고에 시급성 pill과 DEFECT badge가 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByDisplayValue('보수 시급성: 중')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'DEFECT #01' })).toBeTruthy();
  });

  it('AI 경고 배너와 PDF 미리보기 링크가 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByText(AI_DRAFT_WARNING_TITLE)).toBeTruthy();
    expect(screen.getByText((_, node) => node?.textContent === AI_DRAFT_WARNING)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'PDF 미리보기' })).toBeTruthy();
  });

  it('확정 전 미리보기가 뜬 상태에서 페이지 이탈/전환 시 이전 미리보기 blob URL이 즉시 revoke 및 정리된다', async () => {
    const revokeObjectURLSpy = vi.spyOn(URL, 'revokeObjectURL');
    const createObjectURLSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:http://localhost/fake-preview-url');

    reportState = {
      ...mockReport,
      id: 1,
      status: 'DRAFT',
      pdfUrl: null,
      groundingCheckPassed: true,
    };

    const { unmount } = renderPageWithPath('/reports/1?mode=export');

    await waitFor(() => {
      expect(screen.getByTitle('보고서 PDF 미리보기(확정 전)')).toBeTruthy();
    });

    unmount();

    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:http://localhost/fake-preview-url');
    createObjectURLSpy.mockRestore();
    revokeObjectURLSpy.mockRestore();
  });
});

describe('DetailSection', () => {
  it('상세 항목과 현장 이미지 목록을 정상적으로 렌더링한다', () => {
    const content: ReportContent = {
      ...mockContent,
      detail: {
        items: [
          { defect_type: '첫 번째', location: 'A', severity_grade: 'A', description: '', cause: '' },
          { defect_type: '두 번째', location: 'B', severity_grade: 'B', description: '', cause: '' },
        ],
      },
    };
    const imageUrls = ['/images/first.jpg', '/images/second.jpg'];
    render(
      <DetailSection
        content={content}
        onChange={() => {}}
        readOnly={false}
        imageUrls={imageUrls}
      />,
    );

    expect(screen.getAllByRole('img').map((image) => image.getAttribute('src'))).toEqual([
      '/images/first.jpg',
      '/images/second.jpg',
    ]);
    expect(screen.queryByText('이 항목 삭제')).toBeNull();
    expect(screen.queryByText('+ 상세 항목 추가')).toBeNull();
  });
});
