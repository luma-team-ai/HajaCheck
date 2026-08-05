import { reportApi } from '../api/reportApi';
import type { ReportDetailResponse } from '../api/reportApi';
import { isReportContent } from '../types';
import type { ReportContent } from '../types';
import { exportReportToPdf } from './exportReportToPdf';
import {
  getEmptyManualSectionLabels,
  getMissingFinalReportRequiredLabels,
} from './manualSectionValidation';
import { buildReportPdfContext } from './reportPdfContext';
import { buildReportPdfFileName } from '../../../shared/utils/reportPdf';
import { getApiErrorMessage } from '../../../shared/api/types';
import type { InspectionResult } from '../../inspection/types';

export type FinalizeResult =
  | { ok: true; report: ReportDetailResponse }
  | { ok: false; title: string; message: string };

export async function runFinalizeReportFlow(
  reportId: number,
  existingReport?: ReportDetailResponse | null,
  existingContent?: ReportContent | null,
  inspectionData?: InspectionResult | null,
  fallbackOverrides?: { facilityName?: string; inspectionRound?: number },
): Promise<FinalizeResult> {
  let report = existingReport ?? (await reportApi.getReport(reportId)).data;
  if (report.status !== 'DRAFT') {
    return {
      ok: false,
      title: '발행할 수 없음',
      message: 'DRAFT 보고서만 발행할 수 있습니다.',
    };
  }

  const content = existingContent ?? report.content;
  if (!isReportContent(content)) {
    return {
      ok: false,
      title: '발행할 수 없음',
      message: '보고서 본문 형식이 올바르지 않습니다.',
    };
  }

  const emptyManualSectionLabels = getEmptyManualSectionLabels(content);
  if (emptyManualSectionLabels.length > 0) {
    return {
      ok: false,
      title: '확정할 수 없습니다',
      message: `내용이 비어 있거나 필수값이 누락된 추가 섹션이 있습니다:\n${emptyManualSectionLabels.join(', ')}`,
    };
  }

  const missingFinalRequiredLabels = getMissingFinalReportRequiredLabels(content);
  if (missingFinalRequiredLabels.length > 0) {
    return {
      ok: false,
      title: '확정할 수 없습니다',
      message: `최종 보고서 확정 전 필수 항목을 작성해 주세요:\n${missingFinalRequiredLabels.join(', ')}`,
    };
  }

  if (report.groundingCheckPassed !== true) {
    try {
      const recheckResp = await reportApi.groundingRecheck(reportId);
      report = recheckResp.data;
      if (report.groundingCheckPassed !== true) {
        return {
          ok: false,
          title: '검증 실패',
          message: '검증 실패 — 내용을 확인 후 다시 시도하세요.',
        };
      }
    } catch (err: unknown) {
      const message = getApiErrorMessage(err, '확정 검증에 실패했습니다.');
      return {
        ok: false,
        title: '확정 검증 실패',
        message,
      };
    }
  }

  try {
    const includePhoto = content.reportOptions?.includePhoto !== false;
    const pdfContext = buildReportPdfContext(report, inspectionData ?? null, includePhoto, fallbackOverrides);
    const pdfBlob = await exportReportToPdf(content, pdfContext);
    const fileName = buildReportPdfFileName(report.inspectionId);
    const uploadResponse = await reportApi.uploadPdf(reportId, pdfBlob, fileName);
    const finalizeResponse = await reportApi.finalizeReport(reportId, uploadResponse.data.pdfUrl);
    return { ok: true, report: finalizeResponse.data };
  } catch (err: unknown) {
    const message = getApiErrorMessage(err, 'PDF 생성/확정에 실패했습니다.');
    return { ok: false, title: 'PDF 생성/확정 실패', message };
  }
}
