import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import type { ReportDetailResponse } from './reportApi';
import type { ReportContent, ReportListItem, ReportListStatus } from '../types';
import { filterReportListItems } from '../utils/reportListFormat';
import { mockReportListItems } from '../mocks/reportList.mock';
import { mockReportDetailResponse } from '../mocks/reportDetail.mock';

let currentReportState: ReportDetailResponse = mockReportDetailResponse;

export const reportHandlers = [
  http.post('/api/inspections/:inspectionId/reports', ({ params }) => {
    const inspectionId = Number(params.inspectionId);
    currentReportState = { ...currentReportState, inspectionId };
    const body: ApiResponse<ReportDetailResponse> = { success: true, data: currentReportState };
    return HttpResponse.json(body, { status: 201 });
  }),

  http.get('/api/inspections/:inspectionId/reports', ({ params }) => {
    const inspectionId = Number(params.inspectionId);
    const body: ApiResponse<
      {
        id: number;
        inspectionId: number;
        version: number;
        status: 'DRAFT' | 'FINALIZED';
        groundingCheckPassed: boolean;
        createdAt: string;
        createdByName: string;
      }[]
    > = {
      success: true,
      data: [
        {
          id: inspectionId * 100 + 3,
          inspectionId,
          version: 3,
          status: 'FINALIZED',
          groundingCheckPassed: true,
          createdAt: '2026-07-24T14:30:00Z',
          createdByName: '이점검',
        },
        {
          id: inspectionId * 100 + 2,
          inspectionId,
          version: 2,
          status: 'FINALIZED',
          groundingCheckPassed: true,
          createdAt: '2026-07-22T11:20:00Z',
          createdByName: '김관리',
        },
        {
          id: inspectionId * 100 + 1,
          inspectionId,
          version: 1,
          status: 'DRAFT',
          groundingCheckPassed: true,
          createdAt: '2026-07-20T09:00:00Z',
          createdByName: '시스템',
        },
      ],
    };
    return HttpResponse.json(body, { status: 200 });
  }),

  http.get('/api/reports/:id/pdf/:storageKey', () => {
    const dummyPdfContent = `%PDF-1.4
1 0 obj <</Type /Catalog /Pages 2 0 R>> endobj
2 0 obj <</Type /Pages /Kids [3 0 R] /Count 1>> endobj
3 0 obj <</Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources <<>> /Contents 4 0 R>> endobj
4 0 obj <</Length 44>> stream
BT /F1 12 Tf 72 712 Td (HajaCheck Mock PDF Report) Tj ET
endstream endobj
xref
0 5
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000212 00000 n
trailer <</Size 5 /Root 1 0 R>>
startxref
306
%%EOF`;
    return new HttpResponse(dummyPdfContent, {
      status: 200,
      headers: {
        'Content-Type': 'application/pdf',
        'Content-Disposition': 'inline; filename="mock_report.pdf"',
      },
    });
  }),

  // 파라미터 라우트(/api/reports/:id)보다 먼저 선언해야 /summary가 상세 조회로
  // 잘못 매칭되지 않는다.
  http.get('/api/reports/summary', () => {
    const finalizedCount = mockReportListItems.filter((item) => item.status === 'FINALIZED').length;
    const draftCount = mockReportListItems.filter((item) => item.status === 'DRAFT').length;
    const now = new Date();
    const issuedThisMonthCount = mockReportListItems.filter((item) => {
      if (item.status !== 'FINALIZED') return false;
      const updatedAt = new Date(item.updatedAt);
      return updatedAt.getFullYear() === now.getFullYear() && updatedAt.getMonth() === now.getMonth();
    }).length;

    const body: ApiResponse<{
      totalCount: number;
      finalizedCount: number;
      draftCount: number;
      issuedThisMonthCount: number;
    }> = {
      success: true,
      data: {
        totalCount: mockReportListItems.length,
        finalizedCount,
        draftCount,
        issuedThisMonthCount,
      },
    };
    return HttpResponse.json(body);
  }),

  http.get('/api/reports/:id', ({ params }) => {
    const reportId = Number(params.id);
    const body: ApiResponse<ReportDetailResponse> = {
      success: true,
      data: { ...currentReportState, id: reportId },
    };
    return HttpResponse.json(body, { status: 200 });
  }),

  http.patch('/api/reports/:id', async ({ request, params }) => {
    const newContent = (await request.json()) as ReportContent;
    currentReportState = {
      ...currentReportState,
      id: Number(params.id),
      content: newContent,
      groundingCheckPassed: null,
    };
    const body: ApiResponse<ReportDetailResponse> = {
      success: true,
      data: currentReportState,
    };
    return HttpResponse.json(body, { status: 200 });
  }),

  http.post('/api/reports/:id/grounding-recheck', ({ params }) => {
    currentReportState = {
      ...currentReportState,
      id: Number(params.id),
      groundingCheckPassed: true,
    };
    const body: ApiResponse<ReportDetailResponse> = {
      success: true,
      data: currentReportState,
    };
    return HttpResponse.json(body, { status: 200 });
  }),

  http.post('/api/reports/:id/pdf', ({ params }) => {
    const pdfUrl = `/api/reports/${params.id}/pdf/storage-key-123.pdf`;
    const body: ApiResponse<{ pdfUrl: string }> = {
      success: true,
      data: { pdfUrl },
    };
    return HttpResponse.json(body, { status: 200 });
  }),

  http.post('/api/reports/:id/finalize', async ({ request, params }) => {
    const { pdfUrl } = (await request.json()) as { pdfUrl: string };
    currentReportState = {
      ...currentReportState,
      id: Number(params.id),
      status: 'FINALIZED',
      pdfUrl,
    };
    const body: ApiResponse<ReportDetailResponse> = {
      success: true,
      data: currentReportState,
    };
    return HttpResponse.json(body, { status: 200 });
  }),

  // GET /api/reports — 테스트 전용 fallback fixture. 필터/
  // 검색/페이지네이션을 MSW 안에서 실제로 계산한다(다른 mock처럼 파라미터 무시하지 않음 —
  // 화면 개발·수동 테스트가 실제로 동작해야 의미가 있어서).
  http.get('/api/reports', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '10');
    const facilityIdParam = url.searchParams.get('facilityId');
    const statusParam = url.searchParams.get('status') as ReportListStatus | null;
    const query = url.searchParams.get('query')?.trim().toLowerCase();
    const period = url.searchParams.get('period');

    const filtered = filterReportListItems(mockReportListItems, {
      facilityId: facilityIdParam ? Number(facilityIdParam) : undefined,
      status: statusParam ?? undefined,
      query: query ?? undefined,
      period: period as 'ALL' | '1M' | '3M' | '6M' | undefined,
    });

    const content: ReportListItem[] = filtered.slice(page * size, page * size + size);
    const body: ApiResponse<PageResponse<ReportListItem>> = {
      success: true,
      data: { content, page, totalElements: filtered.length },
    };
    return HttpResponse.json(body);
  }),

];
