// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { runFinalizeReportFlow } from './finalizeReportFlow';
import { reportApi } from '../api/reportApi';
import type { ReportDetailResponse } from '../api/reportApi';
import type { ReportContent } from '../types';

vi.mock('../api/reportApi', () => ({
  reportApi: {
    getReport: vi.fn(),
    groundingRecheck: vi.fn(),
    uploadPdf: vi.fn(),
    finalizeReport: vi.fn(),
  },
}));

vi.mock('./exportReportToPdf', () => ({
  exportReportToPdf: vi.fn().mockResolvedValue(new Blob(['fake-pdf'])),
}));

vi.mock('../../../shared/utils/reportPdf', () => ({
  buildReportPdfFileName: vi.fn().mockReturnValue('report.pdf'),
}));

function axiosResponse<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: {} as InternalAxiosRequestConfig,
  };
}

const mockContent: ReportContent = {
  overview: { purpose: '목적', facility_summary: '개요', scope: '범위' },
  summary: {
    overall_opinion: '양호',
    total_count: 1,
    count_by_grade: { A: 1 },
    key_findings: ['균열 발생'],
  },
  detail: { items: [{ defect_type: '균열', location: '1층', severity_grade: 'A', description: '설명', cause: '원인' }] },
  recommendation: {
    items: [{ target: '1층', method: '보수', priority: '상', legal_basis: '', legal_basis_verified: false }],
    monitoring_points: [],
  },
};

const baseReport: ReportDetailResponse = {
  id: 10,
  inspectionId: 1,
  version: 1,
  status: 'DRAFT',
  content: mockContent,
  groundingCheckPassed: true,
  createdBy: 1,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
};

describe('runFinalizeReportFlow unit tests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('groundingCheckPassed가 null일 때 groundingRecheck -> uploadPdf -> finalizeReport 순으로 호출된다', async () => {
    const reportWithNullGrounding: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: null,
    };

    const recheckReport: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: true,
    };

    const finalizedReport: ReportDetailResponse = {
      ...baseReport,
      status: 'FINALIZED',
      pdfUrl: '/api/reports/10/pdf/key',
    };

    vi.mocked(reportApi.groundingRecheck).mockResolvedValueOnce(axiosResponse(recheckReport));
    vi.mocked(reportApi.uploadPdf).mockResolvedValueOnce(
      axiosResponse({ pdfUrl: '/api/reports/10/pdf/key' }),
    );
    vi.mocked(reportApi.finalizeReport).mockResolvedValueOnce(axiosResponse(finalizedReport));

    const result = await runFinalizeReportFlow(10, reportWithNullGrounding, mockContent);

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.report.status).toBe('FINALIZED');
    }
    expect(reportApi.groundingRecheck).toHaveBeenCalledWith(10);
    expect(reportApi.uploadPdf).toHaveBeenCalled();
    expect(reportApi.finalizeReport).toHaveBeenCalledWith(10, '/api/reports/10/pdf/key');
  });

  it('groundingRecheck가 실패하면 uploadPdf와 finalizeReport가 호출되지 않는다', async () => {
    const reportWithNullGrounding: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: null,
    };

    const recheckFailedReport: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: false,
    };

    vi.mocked(reportApi.groundingRecheck).mockResolvedValueOnce(axiosResponse(recheckFailedReport));

    const result = await runFinalizeReportFlow(10, reportWithNullGrounding, mockContent);

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.title).toBe('검증 실패');
    }
    expect(reportApi.groundingRecheck).toHaveBeenCalledWith(10);
    expect(reportApi.uploadPdf).not.toHaveBeenCalled();
    expect(reportApi.finalizeReport).not.toHaveBeenCalled();
  });
});
