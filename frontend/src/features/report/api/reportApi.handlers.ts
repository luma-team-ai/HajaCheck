import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type { ReportDetailResponse } from './reportApi';
import type { ReportContent } from '../types';

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
  http.post('/api/inspections/:inspectionId/reports', () => {
    const body: ApiResponse<ReportDetailResponse> = {
      success: true,
      data: currentReportState,
    };
    return HttpResponse.json(body, { status: 201 });
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
];

