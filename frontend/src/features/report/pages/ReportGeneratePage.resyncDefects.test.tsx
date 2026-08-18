// @vitest-environment jsdom
// #1666 — grounding diff 표시 · '현재 하자 기준으로 다시 맞추기' 버튼 · finalize 실패 복구를 다루는
// 별도 테스트 파일이다. ReportGeneratePage.test.tsx는 이 작업 시작 시점에 이미 2건이 pre-existing
// 실패 상태였다(핸드오프 §주의1) — 그 파일을 수리하는 대신 새 검증을 여기로 분리해 통과 케이스로 한정한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import type { ReportDefectDiff, ReportDefectSyncResponse, ReportDetailResponse } from '../api/reportApi';
import type { DefectDetailItem, InspectionResponse, MediaResponse } from '../../inspection/api/inspectionApi.types';
import type { ReportContent } from '../types';
import { mockReportDetailResponse } from '../mocks/reportDetail.mock';
import { ReportGeneratePage } from './ReportGeneratePage';
import { exportReportToPdf } from '../utils/exportReportToPdf';
import { buildReportPdfFileName } from '../../../shared/utils/reportPdf';

vi.mock('../utils/exportReportToPdf', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/exportReportToPdf')>();
  return {
    ...actual,
    exportReportToPdf: vi.fn().mockResolvedValue(new Blob(['fake-pdf'])),
  };
});

vi.mock('../../../shared/utils/reportPdf', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../shared/utils/reportPdf')>();
  return {
    ...actual,
    buildReportPdfFileName: vi.fn().mockReturnValue('점검보고서_1_20260818.pdf'),
  };
});

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
  groundingCheckPassed: false,
};

const sampleDiff: ReportDefectDiff = {
  missingDefects: [
    { defectId: 9, defectType: 'CRACK', typeLabel: '균열', severityGrade: 'B', location: '2층 슬래브' },
  ],
  extraItems: [{ defectId: 3, defectType: 'SPALLING', severityGrade: 'C' }],
  unmatchedItems: [{ defectType: '누수', severityGrade: 'A' }],
};

let reportState: ReportDetailResponse = mockReport;
let resyncCallCount = 0;
let recheckCallCount = 0;

const server = setupServer(
  http.get('/api/inspections/1', () => HttpResponse.json({ success: true, data: mockInspection })),
  http.get('/api/inspections/1/defects', () => HttpResponse.json({ success: true, data: mockDefects })),
  http.get('/api/inspections/1/media', () => {
    const mockMedia: MediaResponse[] = [];
    return HttpResponse.json({ success: true, data: mockMedia });
  }),
  http.get('/api/facilities/1', () => HttpResponse.json({ success: true, data: mockFacility })),
  http.get('/api/reports/1', () => HttpResponse.json({ success: true, data: reportState })),
  http.patch('/api/reports/1', async ({ request }) => {
    const body = (await request.json()) as { contentJson: string };
    reportState = { ...reportState, content: JSON.parse(body.contentJson), groundingCheckPassed: null };
    return HttpResponse.json({ success: true, data: reportState });
  }),
  http.post('/api/reports/1/grounding-recheck', () => {
    recheckCallCount += 1;
    // 확정 하자와 여전히 불일치한다고 가정 — 배너의 diff 표시를 검증하기 위해 실패를 유지한다.
    reportState = { ...reportState, groundingCheckPassed: false };
    const body: { success: true; data: ReportDefectSyncResponse } = {
      success: true,
      data: { ...reportState, diff: sampleDiff },
    };
    return HttpResponse.json(body);
  }),
  http.post('/api/reports/1/resync-defects', () => {
    resyncCallCount += 1;
    reportState = { ...reportState, groundingCheckPassed: null };
    const body: { success: true; data: ReportDefectSyncResponse } = {
      success: true,
      data: { ...reportState, diff: sampleDiff },
    };
    return HttpResponse.json(body);
  }),
);

beforeAll(() => server.listen());
beforeEach(() => {
  reportState = mockReport;
  resyncCallCount = 0;
  recheckCallCount = 0;
  vi.mocked(exportReportToPdf).mockClear();
  vi.mocked(buildReportPdfFileName).mockClear();
});
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

describe('ReportGeneratePage — grounding diff 표시 · 자동 재구성 버튼(#1666)', () => {
  const renderPage = () => {
    const queryClient = new QueryClient();
    const router = createMemoryRouter(
      [{ path: '/reports/:reportId', element: <ReportGeneratePage /> }],
      { initialEntries: ['/reports/1'] },
    );
    return render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
  };

  it('groundingCheckPassed가 false면 검증 실패 배너와 "현재 하자 기준으로 다시 맞추기" 버튼이 렌더링된다', async () => {
    renderPage();

    await screen.findByText('검증 실패 — 내용을 확인 후 다시 검증하세요.');
    expect(screen.getByRole('button', { name: '현재 하자 기준으로 다시 맞추기' })).toBeTruthy();
  });

  it('최종 보고서 확정 클릭 시 내부 grounding-recheck가 실패하면 diff(누락/잉여/legacy unmatched)가 배너에 목록으로 표시된다', async () => {
    renderPage();
    await screen.findByText('검증 실패 — 내용을 확인 후 다시 검증하세요.');

    fireEvent.click(screen.getByRole('button', { name: /최종 보고서 확정/ }));

    await waitFor(() => {
      expect(recheckCallCount).toBeGreaterThan(0);
    });
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeTruthy();
    });

    expect(screen.getByText(/보고서에 누락된 확정 하자/)).toBeTruthy();
    expect(screen.getByText(/균열 · B등급 · 2층 슬래브/)).toBeTruthy();
    expect(screen.getByText(/더 이상 확정 상태가 아닌 항목/)).toBeTruthy();
    expect(screen.getByText(/SPALLING · C등급/)).toBeTruthy();
    expect(screen.getByText(/비교할 수 없는 기존 항목/)).toBeTruthy();
    expect(screen.getByText(/누수 · A등급/)).toBeTruthy();
  });

  it('"현재 하자 기준으로 다시 맞추기" 클릭 시 resync-defects를 호출해 보고서를 갱신하고 안내 모달을 띄운다', async () => {
    renderPage();
    await screen.findByText('검증 실패 — 내용을 확인 후 다시 검증하세요.');

    fireEvent.click(screen.getByRole('button', { name: '현재 하자 기준으로 다시 맞추기' }));

    await waitFor(() => {
      expect(resyncCallCount).toBe(1);
    });
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeTruthy();
    });
    expect(within(screen.getByRole('dialog')).getByText(/하자 목록을 다시 맞췄습니다/)).toBeTruthy();
    expect(
      within(screen.getByRole('dialog')).getByText(/설명·원인이 비어 있으니 확정 전에 직접 작성해 주세요/),
    ).toBeTruthy();

    // resync-defects는 groundingCheckPassed를 null로 리셋한다(#1653 계약) — 검증 실패 배너가 사라진다.
    await waitFor(() => {
      expect(screen.queryByText('검증 실패 — 내용을 확인 후 다시 검증하세요.')).toBeNull();
    });
  });

  it('저장하지 않은 편집이 있는 상태로 "현재 하자 기준으로 다시 맞추기"를 클릭하면 재구성 대신 안내만 하고 resync-defects를 호출하지 않는다', async () => {
    renderPage();
    await screen.findByText('검증 실패 — 내용을 확인 후 다시 검증하세요.');

    const purposeInput = await screen.findByDisplayValue('정기 점검');
    fireEvent.change(purposeInput, { target: { value: '정기 점검(수정됨)' } });

    fireEvent.click(screen.getByRole('button', { name: '현재 하자 기준으로 다시 맞추기' }));

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeTruthy();
    });
    expect(within(screen.getByRole('dialog')).getByText(/저장하지 않은 변경 사항이 있습니다/)).toBeTruthy();
    expect(resyncCallCount).toBe(0);
  });
});
