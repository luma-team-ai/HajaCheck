// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import type { ReportDetailResponse } from '../api/reportApi';
import type { InspectionResponse, DefectDetailItem, MediaResponse } from '../../inspection/api/inspectionApi.types';
import { isReportContent, type ReportContent } from '../types';
import { AI_DRAFT_WARNING, AI_DRAFT_WARNING_TITLE } from '../constants';
import { mockReportDetailResponse } from '../mocks/reportDetail.mock';
import { ReportGeneratePage } from './ReportGeneratePage';
import { buildReportPdfFileName, exportReportToPdf } from '../utils/exportReportToPdf';
import type { Defect } from '../../inspection/types';
import type { DefectPhotoGroup } from '../components/editor/DefectPhoto';
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
let updateReportCallCount = 0;
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
    updateReportCallCount += 1;
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
  updateReportCallCount = 0;
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
    const router = createMemoryRouter(
      [{ path: '/reports/:reportId', element: <ReportGeneratePage /> }],
      { initialEntries: [path] },
    );
    return render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
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
    renderPageWithPath('/reports/invalid');

    expect(screen.getByText(/잘못된 접근/)).toBeTruthy();
  });

  it('편집 후 최종 보고서 확정 버튼 하나로 저장→확정 검증→PDF 생성 후 확정까지 순차 진행되어 최종 FINALIZED로 전환된다', async () => {
    renderPage();

    await screen.findByText('보고서 생성 결과');

    const saveButton = screen.getByRole('button', { name: '임시저장' }) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);

    const purposeInput = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    fireEvent.change(purposeInput, { target: { value: '수정된 목적' } });
    expect(saveButton.disabled).toBe(false);

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    await waitFor(() => {
      expect(screen.getByText('이 보고서는 확정되어 더 이상 편집할 수 없습니다.')).toBeTruthy();
    });
    expect(updateReportCallCount).toBe(1);

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
    fireEvent.click(screen.getByRole('button', { name: '임시저장' }));

    await waitFor(() => expect(purposeInput.readOnly).toBe(true));
    resolveSave?.();
    await waitFor(() => expect(purposeInput.readOnly).toBe(false));
  });

  it('내용이 비어 있는 추가 섹션은 저장할 수 없다 — AlertModal로 안내하고 저장 API는 호출하지 않는다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));
    fireEvent.click(screen.getByRole('button', { name: '제출문' }));

    const saveButton = screen.getByRole('button', { name: '임시저장' }) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);

    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeTruthy();
    });
    expect(within(screen.getByRole('dialog')).getByText(/제출문/)).toBeTruthy();
    expect(updateReportCallCount).toBe(0);
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

    renderPage();

    await screen.findByDisplayValue(realContractContent.overview.purpose);

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    await waitFor(() => {
      expect(screen.getByText('이 보고서는 확정되어 더 이상 편집할 수 없습니다.')).toBeTruthy();
    });
    expect(exportReportToPdf).toHaveBeenCalledWith(
      {
        ...realContractContent,
        summary: {
          ...realContractContent.summary,
          responsible_engineer_name: '',
        },
      },
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

    renderPageWithPath('/reports/1?mode=export');

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

    renderPageWithPath('/reports/1?mode=export');

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

  // 미리보기는 확정 전 편집 중인 상태를 보기 위한 기능이라, 확정 검증 통과 여부와 무관하게
  // content만 있으면 현재 내용으로 즉석 렌더링해야 한다(확정 검증이 최종 확정 버튼을 누르기
  // 전까지 한 번도 실행되지 않았을 수 있어, 이 게이트를 걸면 신규 보고서는 미리보기를 영영
  // 못 보게 된다).
  it('확정 검증을 통과하지 못한 보고서로 mode=export에 직접 진입해도 현재 내용으로 미리보기를 렌더한다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: null,
      status: 'DRAFT',
      pdfUrl: null,
    };

    renderPageWithPath('/reports/1?mode=export');

    const pdfFrame = await screen.findByTitle('보고서 PDF 미리보기(확정 전)');
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(screen.getByText(/아직 확정되지 않은 미리보기입니다/)).toBeTruthy();
    expect(screen.queryByText(/grounding/i)).toBeNull();
  });

  // 미리보기는 "저장 여부"와도 무관해야 한다 — 임시저장은 라우트 이탈 시 데이터 유실을 막기
  // 위한 방어 장치일 뿐, 편집 중 미리보기를 보는 것과는 별개 목적이다.
  it('저장하지 않은 변경 사항이 있는 상태로 mode=export에 진입해도 현재(미저장) 내용으로 미리보기를 렌더한다', async () => {
    reportState = {
      ...mockReportDetailResponse,
      groundingCheckPassed: null,
      status: 'DRAFT',
      pdfUrl: null,
    };

    const queryClient = new QueryClient();
    const router = createMemoryRouter(
      [{ path: '/reports/:reportId', element: <ReportGeneratePage /> }],
      { initialEntries: ['/reports/1'] },
    );
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByText('보고서 생성 결과');
    const purposeInput = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    fireEvent.change(purposeInput, { target: { value: '미저장 변경' } });

    await router.navigate('/reports/1?mode=export');

    const pdfFrame = await screen.findByTitle('보고서 PDF 미리보기(확정 전)');
    expect(pdfFrame.getAttribute('src')).toContain('blob:');
    expect(screen.queryByText(/grounding/i)).toBeNull();
    expect(screen.queryByText(/확정 검증/)).toBeNull();
  });

  it('확정 검증을 아직 통과하지 못했어도(content가 편집되지 않은 상태) 최종 보고서 확정 버튼은 활성화되어 있다', async () => {
    renderPage();

    await screen.findByText('보고서 생성 결과');

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    expect(screen.queryByRole('button', { name: '확정 검증' })).toBeNull();
  });

  it('저장된 보고서에 내용이 비어 있는 수동 섹션이 있으면 최종 보고서 확정 버튼 클릭 시 어떤 섹션을 채워야 하는지 모달로 안내한다', async () => {
    // #1341 원 설계: 버튼을 비활성화해 이유를 숨기지 않는다 — 항상 클릭 가능하고, 클릭 시
    // AlertModal이 무엇이 비었는지 알려준다(#1375/#1377에서 버튼을 조용히 비활성화하도록 되돌아간
    // 회귀를 #1409에서 원복).
    reportState = {
      ...mockReport,
      groundingCheckPassed: true,
      content: {
        ...mockContent,
        manualSections: [
          {
            id: 'manual-empty-safety',
            type: 'safety-assessment',
            title: '안전성평가 결과',
            data: { body: '' },
          },
        ],
      },
    };

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('확정할 수 없습니다')).toBeTruthy();
    expect(within(dialog).getByText(/필수값이 누락된 추가 섹션.*안전성평가 결과/)).toBeTruthy();
  });

  it('종합 의견이 비어 있으면 최종 보고서 확정 버튼 클릭 시 모달로 안내한다', async () => {
    reportState = {
      ...mockReport,
      groundingCheckPassed: true,
      content: {
        ...mockContent,
        summary: {
          ...mockContent.summary,
          overall_opinion: '   ',
        },
      },
    };

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('확정할 수 없습니다')).toBeTruthy();
    expect(within(dialog).getByText(/결과 요약 > 종합 의견/)).toBeTruthy();
  });

  it('결과 요약 책임기술자는 배정 점검자 이름으로 기본 표시되고 수동 수정할 수 있다', async () => {
    const updatedContent = {
      ...mockContent,
      summary: { ...mockContent.summary, responsible_engineer_name: '박수정' },
    };
    reportState = {
      ...mockReport,
      groundingCheckPassed: true,
      context: {
        ...mockReport.context,
        assignedInspector: { id: 1, name: '김기준', role: 'INSPECTOR' },
        defects: [],
        media: [],
      },
      content: mockContent,
    };
    server.use(
      http.patch('/api/reports/:id', async ({ request }) => {
        updateReportCallCount += 1;
        const body = (await request.json()) as { contentJson: string };
        expect(JSON.parse(body.contentJson)).toEqual(updatedContent);
        return HttpResponse.json({
          success: true,
          data: {
            ...reportState,
            content: updatedContent,
          },
        });
      }),
    );

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const engineerInput = screen.getByLabelText('책임기술자') as HTMLInputElement;
    expect(engineerInput.value).toBe('김기준');
    fireEvent.change(engineerInput, { target: { value: '박수정' } });
    fireEvent.click(screen.getByRole('button', { name: '임시저장' }));

    await waitFor(() => expect(updateReportCallCount).toBe(1));
  });

  it.each([
    {
      label: '기본현황',
      expectedLabel: '기본현황 > 점검 목적',
      content: {
        ...mockContent,
        overview: { ...mockContent.overview, purpose: '   ' },
      },
    },
    {
      label: '진단 외관조사결과 기본사항',
      expectedLabel: '진단 외관조사결과 기본사항 > 하자 #1 설명',
      content: {
        ...mockContent,
        detail: { items: [{ ...mockContent.detail.items[0], description: '' }] },
      },
    },
    {
      label: '보수ㆍ보강(안)',
      expectedLabel: '보수ㆍ보강(안) > 권고 #1 방법',
      content: {
        ...mockContent,
        recommendation: { ...mockContent.recommendation, items: [{ ...mockContent.recommendation.items[0], method: '' }] },
      },
    },
  ])('$label 편집 필드가 비어 있으면 최종 보고서 확정 버튼 클릭 시 모달로 안내한다', async ({ content, expectedLabel }) => {
    reportState = {
      ...mockReport,
      groundingCheckPassed: true,
      content,
    };

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('확정할 수 없습니다')).toBeTruthy();
    expect(within(dialog).getByText(new RegExp(expectedLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))).toBeTruthy();
  });

  it('제출문은 회사명만 있어도 필수값 누락이면 저장과 최종 확정을 막는다', async () => {
    reportState = {
      ...mockReport,
      groundingCheckPassed: true,
      content: {
        ...mockContent,
        manualSections: [
          {
            id: 'manual-submission',
            type: 'submission',
            title: '제출문',
            data: {
              recipient: '',
              contractDate: '',
              companyName: '개발팀 공용 테스트',
              companyAddress: '',
              representativeName: '',
            },
          },
        ],
      },
    };

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const finalizeButton = screen.getByRole('button', { name: /최종 보고서 확정/ }) as HTMLButtonElement;
    expect(finalizeButton.disabled).toBe(false);
    fireEvent.click(finalizeButton);

    const finalizeDialog = await screen.findByRole('dialog');
    expect(within(finalizeDialog).getByText('확정할 수 없습니다')).toBeTruthy();
    expect(within(finalizeDialog).getByText(/필수값이 누락된 추가 섹션.*제출문/)).toBeTruthy();
    fireEvent.click(within(finalizeDialog).getByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.change(screen.getByLabelText('점검 목적'), { target: { value: '제출문 누락 저장 방지' } });
    fireEvent.click(screen.getByRole('button', { name: '임시저장' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeTruthy();
    expect(screen.getByText('저장할 수 없습니다')).toBeTruthy();
    expect(within(dialog).getByText(/필수값이 누락된 추가 섹션.*제출문/)).toBeTruthy();
    expect(updateReportCallCount).toBe(0);
  });

  it('서식 섹션 추가 메뉴에는 결과 요약 하위인 종합의견 및 고정 섹션과 중복되는 항목을 노출하지 않는다', async () => {
    renderPage();

    await screen.findByText('보고서 생성 결과');
    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));

    expect(screen.getByRole('button', { name: '제출문' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '참여 기술진 명단' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '안전성평가 결과' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '현장시험(비파괴 및 추가시험)' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '시설물 현황' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도' })).toBeTruthy();
    // 결과 요약 하위 개념(종합의견)은 별도 항목으로 노출하지 않는다.
    expect(screen.queryByRole('button', { name: '책임기술자 종합의견' })).toBeNull();
    // 고정 섹션과 제목/문구가 그대로 겹치는 수동 섹션 스캐폴딩은 메뉴에서 뺀다(#1409) —
    // 'overview-form'="기본현황"(고정 overview 라벨과 동일), 'inspection-result-repair'/
    // 'member-condition-repair'는 고정 detail 섹션이 이미 표로 렌더링하는 문구와 중복.
    expect(screen.queryByRole('button', { name: '기본현황' })).toBeNull();
    expect(screen.queryByRole('button', { name: '상태평가 결과 및 보수ㆍ보강' })).toBeNull();
    expect(screen.queryByRole('button', { name: '부위별 상태평가 결과 및 보수ㆍ보강' })).toBeNull();
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

    fireEvent.click(screen.getByRole('button', { name: '임시저장' }));

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

    renderPageWithPath('/reports/99');

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

  it('저장하지 않은 편집이 있으면 작성자 확인 단계를 활성화한다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');

    const authorStep = screen.getByText('작성자 확인').closest('li');
    expect(authorStep).not.toBeNull();
    expect(within(authorStep!).getByText('B').className).not.toContain('bg-primary');

    fireEvent.change(screen.getByLabelText('점검 목적'), { target: { value: '수정된 점검 목적' } });

    expect(within(authorStep!).getByText('B').className).toContain('bg-primary');
  });

  it('진단 외관조사결과 기본사항 등급 필터 pills(전체, A, B, C, D, E)가 항상 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    const filterGroup = screen.getByRole('group', { name: '등급 필터' });
    expect(filterGroup).toBeTruthy();
    for (const g of ['전체', 'A', 'B', 'C', 'D', 'E']) {
      expect(within(filterGroup).getByRole('button', { name: new RegExp(`^${g}`) })).toBeTruthy();
    }
  });

  it('진단 외관조사결과 기본사항 페이지네이션 컨트롤이 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    const detailSection = screen.getByText('진단 외관조사결과 기본사항').closest('.rounded-lg') as HTMLElement | null;
    expect(detailSection).toBeTruthy();
    expect(within(detailSection!).getByRole('button', { name: '이전 페이지' })).toBeTruthy();
    expect(within(detailSection!).getByRole('button', { name: '다음 페이지' })).toBeTruthy();
    expect(within(detailSection!).getByText('1', { selector: 'span.font-bold' })).toBeTruthy();
    expect(within(detailSection!).getByText('/ 1', { selector: 'span.text-zinc-500' })).toBeTruthy();
  });

  it('보수ㆍ보강에 시급성 pill과 하자 badge가 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByLabelText('권고 1 보수 시급성').textContent).toBe('보수 시급성: 중');
    expect(screen.getByRole('button', { name: '하자 #01' })).toBeTruthy();
  });

  it('AI 경고 배너와 PDF 미리보기 버튼이 렌더링된다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');
    expect(screen.getByText(AI_DRAFT_WARNING_TITLE)).toBeTruthy();
    expect(screen.getByText((_, node) => node?.textContent === AI_DRAFT_WARNING)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'PDF 미리보기' })).toBeTruthy();
  });

  // #1338 — PDF 미리보기 = 임시저장 + 이동. 저장된 상태(!dirty)면 저장 단계 없이 바로 이동한다.
  it('저장된 상태에서 PDF 미리보기를 클릭하면 임시저장 없이 바로 export 모드로 이동한다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');

    fireEvent.click(screen.getByRole('button', { name: 'PDF 미리보기' }));

    await waitFor(() => {
      expect(screen.queryByLabelText('점검 목적')).toBeNull();
    });
    expect(updateReportCallCount).toBe(0);
  });

  // 미리보기는 편집 중인 내용을 보기 위한 기능이라, 미저장 변경이 있어도 저장을 강제하지 않고
  // 바로 export 모드로 이동해 현재 내용을 렌더한다(임시저장은 라우트 이탈 가드가 별도로 담당).
  it('미저장 변경이 있는 상태에서 PDF 미리보기를 클릭하면 저장 없이 바로 export 모드로 이동한다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');

    const purposeInput = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    fireEvent.change(purposeInput, { target: { value: '미리보기 전 저장될 내용' } });

    fireEvent.click(screen.getByRole('button', { name: 'PDF 미리보기' }));

    await waitFor(() => {
      expect(screen.queryByLabelText('점검 목적')).toBeNull();
    });
    expect(updateReportCallCount).toBe(0);
  });

  it('미저장 변경 상태에서 PDF 내보내기가 아닌 다른 라우트로 이탈하면 임시저장 모달을 띄우고 저장 후 이동한다', async () => {
    const queryClient = new QueryClient();
    const router = createMemoryRouter(
      [
        { path: '/reports/:reportId', element: <ReportGeneratePage /> },
        { path: '/dashboard', element: <div>대시보드 페이지</div> },
      ],
      { initialEntries: ['/reports/1'] },
    );
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByText('보고서 생성 결과');
    fireEvent.change(screen.getByLabelText('점검 목적'), { target: { value: '이탈 전 저장' } });

    await router.navigate('/dashboard');

    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(screen.getByText('편집한 내용이 저장되지 않았습니다')).toBeTruthy();
    expect(screen.getByText('이 페이지를 나가기 전에 변경 내용을 임시저장합니다.')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/reports/1');
    expect(updateReportCallCount).toBe(0);

    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(router.state.location.pathname).toBe('/reports/1');

    await router.navigate('/dashboard');
    await screen.findByText('편집한 내용이 저장되지 않았습니다');
    fireEvent.click(screen.getByRole('button', { name: '임시저장 후 나가기' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'));
    expect(screen.getByText('대시보드 페이지')).toBeTruthy();
    expect(updateReportCallCount).toBe(1);
  });

  it('미저장 상태의 PDF 미리보기에서도 다른 라우트 이탈 시 같은 임시저장 모달을 띄운다', async () => {
    const queryClient = new QueryClient();
    const router = createMemoryRouter(
      [
        { path: '/reports/:reportId', element: <ReportGeneratePage /> },
        { path: '/dashboard', element: <div>대시보드 페이지</div> },
      ],
      { initialEntries: ['/reports/1'] },
    );
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByText('보고서 생성 결과');
    fireEvent.change(screen.getByLabelText('점검 목적'), { target: { value: '미리보기 이탈 전 저장' } });

    await router.navigate('/reports/1?mode=export');
    await screen.findByTitle('보고서 PDF 미리보기(확정 전)');

    await router.navigate('/dashboard');

    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(screen.getByText('편집한 내용이 저장되지 않았습니다')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/reports/1');
    expect(router.state.location.search).toBe('?mode=export');
  });

  // 회귀 테스트 — 미리보기(mode=export)에서 편집 화면으로 "뒤로가기"할 때는 같은 컴포넌트가
  // 유지돼 content가 그대로 남아있으므로 임시저장 모달이 뜨면 안 된다(이전엔 편도로만
  // 예외 처리돼 있어 이 방향에서만 잘못 떴었다).
  it('미저장 상태에서 미리보기 → 편집 화면으로 돌아갈 때는 임시저장 모달을 띄우지 않는다', async () => {
    const queryClient = new QueryClient();
    const router = createMemoryRouter(
      [{ path: '/reports/:reportId', element: <ReportGeneratePage /> }],
      { initialEntries: ['/reports/1'] },
    );
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await screen.findByText('보고서 생성 결과');
    fireEvent.change(screen.getByLabelText('점검 목적'), { target: { value: '뒤로가기 전 미저장 변경' } });

    await router.navigate('/reports/1?mode=export');
    await screen.findByTitle('보고서 PDF 미리보기(확정 전)');

    await router.navigate('/reports/1');

    await screen.findByLabelText('점검 목적');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(router.state.location.search).toBe('');
  });

  // 빈 추가 섹션이 있어도 미리보기는 저장/확정 검증과 무관하게 봐야 하므로 AlertModal 없이
  // 바로 현재 내용으로 렌더한다. 빈 섹션 검증은 "저장"과 "최종 확정" 시점에만 걸린다
  // (handleSave/handleFinalizeAll — 별도 테스트에서 커버).
  it('미저장 변경에 빈 추가 섹션이 있어도 PDF 미리보기는 AlertModal 없이 바로 export 모드로 이동한다', async () => {
    renderPage();
    await screen.findByText('보고서 생성 결과');

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));
    fireEvent.click(screen.getByRole('button', { name: '제출문' }));

    fireEvent.click(screen.getByRole('button', { name: 'PDF 미리보기' }));

    await waitFor(() => {
      expect(screen.queryByLabelText('점검 목적')).toBeNull();
    });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(updateReportCallCount).toBe(0);
  });

  // #1338 — 최종 확정 통합 플로우 중 확정 검증이 groundingCheckPassed=false를 반환하면 AlertModal로
  // 알리고 PDF 생성/업로드/확정 단계로 진행하지 않는다.
  it('확정 검증이 통과하지 못하면 최종 확정 플로우가 AlertModal을 띄우고 PDF 생성 단계로 진행하지 않는다', async () => {
    server.use(
      http.post('/api/reports/1/grounding-recheck', () => {
        reportState = { ...reportState, groundingCheckPassed: false };
        return HttpResponse.json({ success: true, data: reportState });
      }),
    );

    renderPage();
    await screen.findByText('보고서 생성 결과');

    fireEvent.click(screen.getByRole('button', { name: /최종 보고서 확정/ }));

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeTruthy();
    });
    expect(within(screen.getByRole('dialog')).getByRole('alert').textContent).toContain(
      '내용을 확인 후 다시 시도하세요',
    );
    expect(exportReportToPdf).not.toHaveBeenCalled();
    expect(uploadedPdfFileName).toBeNull();
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

  // 회귀 테스트(#1379) — detail.items 순서가 실제 defects 목록 순서와 다를 때(AI 재생성 등으로
  // 흔히 발생) defect_id로 정확히 매칭해야 한다. 예전엔 배열 인덱스로만 짝지어서, 순서가 어긋나면
  // 엉뚱한 하자의 사진·bbox가 표시됐다.
  it('detail.items 순서가 defects 순서와 달라도 defect_id로 올바른 사진과 매칭한다', async () => {
    server.use(
      http.get('/api/inspections/1/defects', () =>
        HttpResponse.json({
          success: true,
          data: [
            {
              id: 5, inspectionId: 1, type: 'CRACK', grade: 'A', status: 'DETECTED', confidence: 0.9,
              isReviewed: false, bboxX: 0.1, bboxY: 0.1, bboxW: 0.1, bboxH: 0.1,
              mediaId: 100, imageUrl: '/img/a-grade.jpg', createdAt: '2026-07-22T10:00:00Z',
            },
            {
              id: 9, inspectionId: 1, type: 'CRACK', grade: 'E', status: 'DETECTED', confidence: 0.9,
              isReviewed: false, bboxX: 0.2, bboxY: 0.2, bboxW: 0.1, bboxH: 0.1,
              mediaId: 200, imageUrl: '/img/e-grade.jpg', createdAt: '2026-07-22T10:00:00Z',
            },
          ],
        }),
      ),
    );
    reportState = {
      ...mockReport,
      content: {
        ...mockContent,
        detail: {
          // defects 응답과 반대 순서(9번이 먼저) — defect_id 없이 인덱스로만 매칭했다면
          // 첫 항목에 5번(A등급/a-grade.jpg)의 사진이 잘못 뜬다.
          items: [
            { defect_id: 9, defect_type: '균열', location: 'E', severity_grade: 'E', description: '', cause: '' },
            { defect_id: 5, defect_type: '균열', location: 'A', severity_grade: 'A', description: '', cause: '' },
          ],
        },
      },
    };

    renderPage();
    await screen.findByText('보고서 생성 결과');

    const images = await screen.findAllByRole('img', { name: /현장 이미지/ });
    expect(images.map((image) => image.getAttribute('src'))).toEqual([
      '/img/e-grade.jpg',
      '/img/a-grade.jpg',
    ]);
  });

  it('detail.items에 defect_id가 있으면 매칭 실패 시 인덱스 사진으로 폴백하지 않는다', async () => {
    server.use(
      http.get('/api/inspections/1/defects', () =>
        HttpResponse.json({
          success: true,
          data: [
            {
              id: 5, inspectionId: 1, type: 'CRACK', grade: 'A', status: 'DETECTED', confidence: 0.9,
              isReviewed: false, bboxX: 0.1, bboxY: 0.1, bboxW: 0.1, bboxH: 0.1,
              mediaId: 100, imageUrl: '/img/a-grade.jpg', createdAt: '2026-07-22T10:00:00Z',
            },
          ],
        }),
      ),
    );
    reportState = {
      ...mockReport,
      content: {
        ...mockContent,
        detail: {
          items: [
            { defect_id: 999, defect_type: '균열', location: '삭제된 하자', severity_grade: 'A', description: '', cause: '' },
          ],
        },
      },
    };

    renderPage();
    await screen.findByText('보고서 생성 결과');

    expect(screen.getByText('이미지 없음')).toBeTruthy();
    expect(screen.queryByRole('img', { name: /현장 이미지/ })).toBeNull();
  });
});

/** DetailSection이 쓰는 최소 필드만 채운 사진 그룹 — bbox가 null이면 박스를 그리지 않는 경로를 검증한다. */
function buildDefectPhotoGroup(
  imageUrl: string,
  defectId: number,
  bbox: { x: number; y: number; width: number; height: number } | null,
): DefectPhotoGroup {
  const defect = {
    id: defectId,
    type: '균열',
    grade: 'C',
    status: 'DETECTED',
    confidence: 0.9,
    bbox,
    summary: '',
    mediaId: defectId,
    imageUrl,
  } as unknown as Defect;
  return { mediaId: defectId, imageUrl, defects: [defect], highlightDefectId: defectId };
}

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
    const defectPhotos = [
      buildDefectPhotoGroup('/images/first.jpg', 1, { x: 0.1, y: 0.2, width: 0.3, height: 0.4 }),
      buildDefectPhotoGroup('/images/second.jpg', 2, { x: 0.5, y: 0.5, width: 0.2, height: 0.2 }),
    ];
    render(
      <DetailSection
        content={content}
        onChange={() => {}}
        readOnly={false}
        defectPhotos={defectPhotos}
      />,
    );

    expect(screen.getAllByRole('img').map((image) => image.getAttribute('src'))).toEqual([
      '/images/first.jpg',
      '/images/second.jpg',
    ]);
    expect(screen.queryByText('이 항목 삭제')).toBeNull();
    expect(screen.queryByText('+ 상세 항목 추가')).toBeNull();
  });

  // #1333 — 사진만 나오고 박스가 안 그려지던 회귀를 고정한다. bbox는 % 인라인 스타일로만
  // 표현되므로(테스트용 role/label이 없음) 스타일 값으로 검증한다.
  it('하자 bbox를 사진 위에 박스로 그린다', () => {
    const content: ReportContent = {
      ...mockContent,
      detail: {
        items: [{ defect_type: '균열', location: 'A', severity_grade: 'C', description: '', cause: '' }],
      },
    };
    const { container } = render(
      <DetailSection
        content={content}
        onChange={() => {}}
        readOnly={false}
        defectPhotos={[
          buildDefectPhotoGroup('/images/first.jpg', 1, { x: 0.25, y: 0.5, width: 0.1, height: 0.2 }),
        ]}
      />,
    );

    const box = container.querySelector('span[aria-hidden="true"].absolute') as HTMLElement | null;
    expect(box).not.toBeNull();
    expect(box?.style.left).toBe('25%');
    expect(box?.style.top).toBe('50%');
    expect(box?.style.width).toBe('10%');
    expect(box?.style.height).toBe('20%');
  });

  it('bbox가 없는 하자는 사진만 그리고 박스를 만들지 않는다', () => {
    const content: ReportContent = {
      ...mockContent,
      detail: {
        items: [{ defect_type: '균열', location: 'A', severity_grade: 'C', description: '', cause: '' }],
      },
    };
    const { container } = render(
      <DetailSection
        content={content}
        onChange={() => {}}
        readOnly={false}
        defectPhotos={[buildDefectPhotoGroup('/images/first.jpg', 1, null)]}
      />,
    );

    expect(screen.getAllByRole('img')).toHaveLength(1);
    expect(container.querySelector('span[aria-hidden="true"].absolute')).toBeNull();
  });
});
