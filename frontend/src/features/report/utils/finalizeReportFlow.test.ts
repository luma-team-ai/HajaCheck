// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { runFinalizeReportFlow } from './finalizeReportFlow';
import { reportApi } from '../api/reportApi';
import type { ReportDefectDiff, ReportDefectSyncResponse, ReportDetailResponse } from '../api/reportApi';
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

const emptyDiff: ReportDefectDiff = { missingDefects: [], extraItems: [], unmatchedItems: [] };

describe('runFinalizeReportFlow unit tests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('groundingCheckPassed가 null일 때 groundingRecheck -> uploadPdf -> finalizeReport 순으로 호출된다', async () => {
    const reportWithNullGrounding: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: null,
    };

    const recheckReport: ReportDefectSyncResponse = {
      ...baseReport,
      groundingCheckPassed: true,
      diff: emptyDiff,
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

  it('PDF 생성/확정 단계가 ApiError로 거부되면 서버가 준 구체 사유가 결과 message로 노출된다', async () => {
    const reportWithNullGrounding: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: null,
    };

    const recheckReport: ReportDefectSyncResponse = {
      ...baseReport,
      groundingCheckPassed: true,
      diff: emptyDiff,
    };

    vi.mocked(reportApi.groundingRecheck).mockResolvedValueOnce(axiosResponse(recheckReport));
    // axios 응답 인터셉터(shared/api/axios.ts)는 실패를 { code, message, status } 평탄화해서
    // reject하므로 그 형태로 거부시킨다 — getApiErrorMessage가 이 message를 그대로 전달해야 한다.
    vi.mocked(reportApi.uploadPdf).mockRejectedValueOnce({
      code: 'PDF_UPLOAD_FAILED',
      message: '업로드된 PDF가 없습니다.',
      status: 400,
    });

    const result = await runFinalizeReportFlow(10, reportWithNullGrounding, mockContent);

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.title).toBe('PDF 생성/확정 실패');
      expect(result.message).toBe('업로드된 PDF가 없습니다.');
    }
    expect(reportApi.finalizeReport).not.toHaveBeenCalled();
  });

  it('groundingRecheck가 실패하면 uploadPdf와 finalizeReport가 호출되지 않는다', async () => {
    const reportWithNullGrounding: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: null,
    };

    const diffOnMismatch: ReportDefectDiff = {
      missingDefects: [
        { defectId: 5, defectType: 'CRACK', typeLabel: '균열', severityGrade: 'C', location: '1층 슬래브' },
      ],
      extraItems: [],
      unmatchedItems: [],
    };

    const recheckFailedReport: ReportDefectSyncResponse = {
      ...baseReport,
      groundingCheckPassed: false,
      diff: diffOnMismatch,
    };

    vi.mocked(reportApi.groundingRecheck).mockResolvedValueOnce(axiosResponse(recheckFailedReport));

    const result = await runFinalizeReportFlow(10, reportWithNullGrounding, mockContent);

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.title).toBe('검증 실패');
      // #1666 — recheck 응답의 diff가 실패 결과에 그대로 실려 배너에서 소비할 수 있어야 한다.
      expect(result.diff).toEqual(diffOnMismatch);
    }
    expect(reportApi.groundingRecheck).toHaveBeenCalledWith(10);
    expect(reportApi.uploadPdf).not.toHaveBeenCalled();
    expect(reportApi.finalizeReport).not.toHaveBeenCalled();
  });

  it('#1666 — 확정 요청이 응답 유실 등으로 실패해도 서버 재조회 결과 이미 FINALIZED면 성공으로 복구한다', async () => {
    const recheckReport: ReportDefectSyncResponse = {
      ...baseReport,
      groundingCheckPassed: true,
      diff: emptyDiff,
    };
    const recoveredReport: ReportDetailResponse = {
      ...baseReport,
      status: 'FINALIZED',
      pdfUrl: '/api/reports/10/pdf/key',
    };

    vi.mocked(reportApi.groundingRecheck).mockResolvedValueOnce(axiosResponse(recheckReport));
    vi.mocked(reportApi.uploadPdf).mockResolvedValueOnce(
      axiosResponse({ pdfUrl: '/api/reports/10/pdf/key' }),
    );
    // 실제로는 서버에 도달해 확정까지 끝났지만 네트워크 단절 등으로 응답만 유실된 상황을 재현한다.
    vi.mocked(reportApi.finalizeReport).mockRejectedValueOnce(new Error('network error'));
    vi.mocked(reportApi.getReport).mockResolvedValueOnce(axiosResponse(recoveredReport));

    const reportWithNullGrounding: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: null,
    };
    const result = await runFinalizeReportFlow(10, reportWithNullGrounding, mockContent);

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.report.status).toBe('FINALIZED');
      expect(result.report.pdfUrl).toBe('/api/reports/10/pdf/key');
    }
    expect(reportApi.getReport).toHaveBeenCalledWith(10);
  });

  it('#1666 — 확정 요청 실패 후 재조회해도 여전히 DRAFT면 원래 실패 메시지를 그대로 보고한다', async () => {
    const recheckReport: ReportDefectSyncResponse = {
      ...baseReport,
      groundingCheckPassed: true,
      diff: emptyDiff,
    };
    const stillDraftReport: ReportDetailResponse = {
      ...baseReport,
      status: 'DRAFT',
    };

    vi.mocked(reportApi.groundingRecheck).mockResolvedValueOnce(axiosResponse(recheckReport));
    vi.mocked(reportApi.uploadPdf).mockResolvedValueOnce(
      axiosResponse({ pdfUrl: '/api/reports/10/pdf/key' }),
    );
    vi.mocked(reportApi.finalizeReport).mockRejectedValueOnce({
      code: 'FINALIZE_FAILED',
      message: '확정에 실패했습니다.',
      status: 500,
    });
    vi.mocked(reportApi.getReport).mockResolvedValueOnce(axiosResponse(stillDraftReport));

    const reportWithNullGrounding: ReportDetailResponse = {
      ...baseReport,
      groundingCheckPassed: null,
    };
    const result = await runFinalizeReportFlow(10, reportWithNullGrounding, mockContent);

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.title).toBe('PDF 생성/확정 실패');
      expect(result.message).toBe('확정에 실패했습니다.');
    }
  });
});
