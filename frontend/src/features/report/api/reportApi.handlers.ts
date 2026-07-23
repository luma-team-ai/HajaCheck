import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import type { ReportDetailResponse } from './reportApi';

const mockReportDetail: ReportDetailResponse = {
  id: 1,
  inspectionId: 1,
  version: 1,
  content: {},
  status: 'DRAFT',
  groundingCheckPassed: null,
  pdfUrl: null,
  editedBy: null,
  createdBy: 1,
  createdAt: '2026-07-23T00:00:00Z',
};

export const reportHandlers = [
  http.post('/api/inspections/:inspectionId/reports', () => {
    const body: ApiResponse<ReportDetailResponse> = {
      success: true,
      data: mockReportDetail,
    };
    return HttpResponse.json(body, { status: 201 });
  }),
];
