import { http, HttpResponse } from 'msw';
import type { ApiResponse, PageResponse } from '../../../shared/api/types';
import type { ReportDetailResponse } from './reportApi';
import type { ReportContent, ReportListItem, ReportListStatus } from '../types';
import { filterReportListItems } from '../utils/reportListFormat';
import { mockReportListItems } from '../mocks/reportList.mock';

const mockReportContent: ReportContent = {
  overview: {
    purpose: '시설물 정밀안전점검 및 하자 진단 보고서 작성',
    facility_summary: '서울시 강남구 테헤란로 123 하자체크 타워 (지상 15층, 지하 2층)',
    scope: '주요 구조부(슬래브, 기둥, 외벽) 및 마감재 하자 전수 조사',
  },
  summary: {
    overall_opinion: '주요 구조부의 미세 균열 및 마감재 박리 현상이 일부 확인되었으나, 전체적인 구조 안전성에는 이상이 없습니다.',
    total_count: 3,
    count_by_grade: { A: 0, B: 1, C: 2, D: 0, E: 0 },
    key_findings: [
      '3층 슬래브 0.3mm 건식 균열 발생',
      '외벽 마감재 일부분 박리 및 탈락 위험 관찰',
      '지하 1층 주차장 슬래브 미세 누수 흔적',
    ],
  },
  detail: {
    items: [
      {
        defect_type: '균열',
        location: '3층 슬래브 중앙부',
        severity_grade: 'C',
        description: '폭 0.3mm, 길이 1.2m의 건식 균열',
        cause: '콘크리트 건조수축 및 모멘트 하중 작용',
      },
      {
        defect_type: '박리',
        location: '외벽 북측 마감 타일',
        severity_grade: 'B',
        description: '타일 마감재 부풀음 및 들뜸 현상',
        cause: '동결융해 반복 및 에폭시 접착력 저하',
      },
      {
        defect_type: '누수',
        location: '지하 1층 주차장 천장',
        severity_grade: 'C',
        description: '슬래브 조인트 부위 미세 누수 및 백화 현상',
        cause: '방수층 균열 및 누수 경로 형성',
      },
    ],
  },
  recommendation: {
    items: [
      {
        target: '3층 슬래브 균열',
        method: '에폭시 수지 주입 공법 적용 보수',
        priority: '높음',
        legal_basis: '시설물의 안전 및 유지관리에 관한 특별법 시행령 제12조',
        legal_basis_verified: true,
      },
      {
        target: '외벽 마감재 박리',
        method: '들뜬 마감재 철거 및 재시공',
        priority: '보통',
        legal_basis: '건축물의 안전관리 및 유지관리 기준 제8조',
        legal_basis_verified: true,
      },
    ],
    monitoring_points: [
      '균열 가늠자(Crack Gauge) 설치 후 주기적 진행성 관측',
      '우천 시 지하 주차장 누수 부위 추가 진전 모니터링',
    ],
  },
};

let currentReportState: ReportDetailResponse = {
  id: 1,
  inspectionId: 1,
  version: 1,
  content: mockReportContent,
  status: 'DRAFT',
  groundingCheckPassed: true,
  pdfUrl: null,
  editedBy: null,
  createdBy: 1,
  createdAt: '2026-07-26T10:00:00Z',
};

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

  // GET /api/reports/summary — KPI 4종. 필터와 무관하게 항상 전체 스코프 기준.
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
];
