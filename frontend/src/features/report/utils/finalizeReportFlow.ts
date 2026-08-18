import { reportApi } from '../api/reportApi';
import type { ReportDefectDiff, ReportDetailResponse } from '../api/reportApi';
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
  // report/diff는 groundingRecheck가 실패(검증 실패)했을 때만 채워진다(#1666 리뷰 P1 픽스) —
  // 호출부가 이 report로 로컬 상태를 갱신해야 groundingCheckPassed=false가 반영되고 배너·diff가
  // 실제로 화면에 뜬다. recheck 자체가 호출되지 않은 다른 실패(발행 불가·필수값 누락·확정 검증
  // 자체 실패·PDF 생성/확정 실패)는 report/diff가 없다.
  | { ok: false; title: string; message: string; report?: ReportDetailResponse; diff?: ReportDefectDiff };

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
          report: recheckResp.data,
          diff: recheckResp.data.diff,
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
    // 응답 유실 복구(#1666) — 확정 요청 자체는 서버에 도달해 처리됐지만(네트워크 단절 등으로) 응답만
    // 유실된 경우, existingReport 캐시(호출 시점의 로컬 상태)를 신뢰해 실패로 잘못 보고하지 않도록
    // 서버를 재조회해 실제 상태를 확인한다. finalizeReport의 멱등 분기(FINALIZED+pdfUrl이면 그대로
    // 반환)와 페어 — 재조회 결과가 이미 FINALIZED면 재시도 없이 성공으로 취급한다.
    try {
      const latest = await reportApi.getReport(reportId);
      if (latest.data.status === 'FINALIZED') {
        return { ok: true, report: latest.data };
      }
    } catch {
      // 재조회 자체가 실패하면 원래 에러 메시지로 폴백한다(아래).
    }
    const message = getApiErrorMessage(err, 'PDF 생성/확정에 실패했습니다.');
    return { ok: false, title: 'PDF 생성/확정 실패', message };
  }
}
