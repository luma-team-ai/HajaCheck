import { useCallback, useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { useInspectionResult } from '../../inspection/hooks/useInspectionResult';
import { reportApi } from '../api/reportApi';
import type { ReportDetailResponse } from '../api/reportApi';
import { ReportContentEditor } from '../components/ReportContentEditor';
import { ReportDocument } from '../components/ReportDocument';
import { AI_DRAFT_WARNING, AI_DRAFT_WARNING_TITLE } from '../constants';
import { isReportContent } from '../types';
import type { ReportContent } from '../types';
import { buildReportPdfFileName, exportReportToPdf } from '../utils/exportReportToPdf';

function extractErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'message' in err && typeof err.message === 'string' && err.message) {
    return err.message;
  }
  if (err instanceof Error && err.message) {
    return err.message;
  }
  return fallback;
}

export function ReportGeneratePage() {
  const { id, reportId: routeReportId } = useParams<{ id?: string; reportId?: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const routeInspectionId = Number(id);
  const reportIdParam = routeReportId ?? searchParams.get('reportId');
  const parsedReportId = Number(reportIdParam);
  const hasValidReportId = Number.isInteger(parsedReportId) && parsedReportId > 0;
  const isExportMode = searchParams.get('mode') === 'export';

  const [report, setReport] = useState<ReportDetailResponse | null>(null);
  const [content, setContent] = useState<ReportContent | null>(null);
  const [savedContent, setSavedContent] = useState<ReportContent | null>(null);
  const [isGeneratingDraft, setIsGeneratingDraft] = useState(false);
  const [draftError, setDraftError] = useState<string | null>(null);

  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [isRechecking, setIsRechecking] = useState(false);
  const [recheckError, setRecheckError] = useState<string | null>(null);
  const [isFinalizing, setIsFinalizing] = useState(false);
  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const [isDownloadingPdf, setIsDownloadingPdf] = useState(false);
  const inspectionId = Number.isInteger(routeInspectionId) && routeInspectionId > 0
    ? routeInspectionId
    : report?.inspectionId ?? 0;

  const { data: inspectionData, isLoading: isInspectionLoading } = useInspectionResult(inspectionId);

  const applyReport = useCallback((data: ReportDetailResponse) => {
    setReport(data);
    if (isReportContent(data.content)) {
      setContent(data.content);
      setSavedContent(data.content);
    }
  }, []);

  const reportQuery = useQuery({
    queryKey: ['report', parsedReportId],
    queryFn: ({ signal }) => reportApi.getReport(parsedReportId, signal).then((response) => response.data),
    enabled: hasValidReportId,
    retry: false,
  });

  useEffect(() => {
    if (reportQuery.data) applyReport(reportQuery.data);
  }, [applyReport, reportQuery.data]);

  const handleGenerateReport = async () => {
    if (isGeneratingDraft) return;

    setIsGeneratingDraft(true);
    setDraftError(null);
    try {
      const response = await reportApi.generateReportDraft(inspectionId);
      applyReport(response.data);
    } catch (err) {
      setDraftError(extractErrorMessage(err, '보고서 생성에 실패했습니다.'));
    } finally {
      setIsGeneratingDraft(false);
    }
  };

  const dirty = content !== null && savedContent !== null && JSON.stringify(content) !== JSON.stringify(savedContent);
  const isFinalized = report?.status === 'FINALIZED';

  const handleSave = async () => {
    if (!report || !content || isSaving) return;
    setIsSaving(true);
    setSaveError(null);
    try {
      const response = await reportApi.updateContent(report.id, content);
      applyReport(response.data);
    } catch (err) {
      setSaveError(extractErrorMessage(err, '저장에 실패했습니다.'));
    } finally {
      setIsSaving(false);
    }
  };

  const handleGroundingRecheck = async () => {
    if (!report || isRechecking) return;
    setIsRechecking(true);
    setRecheckError(null);
    try {
      const response = await reportApi.groundingRecheck(report.id);
      applyReport(response.data);
    } catch (err) {
      setRecheckError(extractErrorMessage(err, '확정 검증에 실패했습니다.'));
    } finally {
      setIsRechecking(false);
    }
  };

  const handleGeneratePdfAndFinalize = async () => {
    if (!report || !content || isFinalizing || report.groundingCheckPassed !== true) return;
    setIsFinalizing(true);
    setFinalizeError(null);
    try {
      const pdfBlob = await exportReportToPdf(content);
      const fileName = buildReportPdfFileName(report.inspectionId);
      const uploadResponse = await reportApi.uploadPdf(report.id, pdfBlob, fileName);
      const finalizeResponse = await reportApi.finalizeReport(report.id, uploadResponse.data.pdfUrl);
      applyReport(finalizeResponse.data);
    } catch (err) {
      setFinalizeError(extractErrorMessage(err, 'PDF 생성/확정에 실패했습니다.'));
    } finally {
      setIsFinalizing(false);
    }
  };

  const handleExportPdf = async () => {
    if (!report || isFinalizing || isDownloadingPdf) return;
    if (isFinalized && report.pdfUrl) {
      setIsDownloadingPdf(true);
      setFinalizeError(null);
      try {
        const response = await fetch(report.pdfUrl, { credentials: 'include' });
        if (!response.ok) throw new Error(`PDF ${response.status}`);
        const blob = await response.blob();
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = buildReportPdfFileName(report.inspectionId);
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        URL.revokeObjectURL(objectUrl);
      } catch (err) {
        setFinalizeError(extractErrorMessage(err, 'PDF 내보내기에 실패했습니다.'));
      } finally {
        setIsDownloadingPdf(false);
      }
      return;
    }
    await handleGeneratePdfAndFinalize();
  };

  const handleBackToViewer = () => {
    if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
      navigate('/reports');
      return;
    }
    navigate(`/inspections/${inspectionId}/viewer`);
  };

  if (!hasValidReportId && (!Number.isInteger(inspectionId) || inspectionId <= 0)) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>
    );
  }

  if (isGeneratingDraft || reportQuery.isLoading || (inspectionId > 0 && isInspectionLoading)) {
    return <AILoadingIndicator message="보고서를 생성 중입니다..." />;
  }

  if (reportQuery.isError || draftError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <AIErrorFallback onRetry={draftError ? handleGenerateReport : () => void reportQuery.refetch()} />
        {draftError && <p className="text-sm text-red-600">{draftError}</p>}
        <Button onClick={handleBackToViewer} variant="secondary">
          분석 화면으로 돌아가기
        </Button>
      </div>
    );
  }

  if (!report) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <p className="text-text-muted">점검 결과를 바탕으로 보고서 초안을 생성합니다.</p>
        <Button onClick={handleGenerateReport} variant="primary" size="lg" disabled={isGeneratingDraft}>
          보고서 초안 생성
        </Button>
        <Button onClick={handleBackToViewer} variant="secondary">
          분석 화면으로 돌아가기
        </Button>
      </div>
    );
  }

  const defectDistribution = inspectionData?.defects.reduce(
    (acc, defect) => {
      acc[defect.grade] = (acc[defect.grade] || 0) + 1;
      return acc;
    },
    {} as Record<string, number>,
  ) || {};

  const progressPercent =
    inspectionData && inspectionData.totalCount > 0
      ? (inspectionData.reviewedCount / inspectionData.totalCount) * 100
      : 0;

  const canFinalize = report.groundingCheckPassed === true && !dirty && !isFinalized;

  if (isExportMode && content) {
    return (
      <div className="flex min-h-full flex-col bg-neutral-50">
        <div className="flex items-center justify-between border-b border-zinc-200 bg-white/70 px-6 py-2 backdrop-blur-[10px]">
          <div className="flex items-center gap-2 text-base font-medium text-neutral-600">
            <span>자동 저장됨 · 방금 전</span>
          </div>
          <div className="flex items-center gap-3">
            <Link
              to={`/reports/${report.id}`}
              className="rounded-full border border-zinc-200 bg-white px-4 py-1.5 text-base font-medium text-zinc-900 no-underline"
            >
              편집·미리보기
            </Link>
            <Button
              onClick={() => void handleExportPdf()}
              variant="primary"
              disabled={isDownloadingPdf || isFinalizing || (!isFinalized && !canFinalize)}
            >
              {isFinalizing || isDownloadingPdf ? 'PDF 내보내는 중...' : 'PDF 내보내기'}
            </Button>
          </div>
        </div>
        <div className="flex flex-1 justify-center overflow-auto bg-zinc-100 p-8">
          <ReportDocument content={content} report={report} inspectionData={inspectionData} />
        </div>
        {finalizeError && <p className="m-0 bg-white px-6 py-2 text-sm text-red-600">{finalizeError}</p>}
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col gap-6 py-6 pl-6 pr-28">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-text-default">보고서 편집</h1>
        <Button onClick={handleBackToViewer} variant="secondary" size="md">
          분석 화면으로 돌아가기
        </Button>
      </div>

      {/* AI 초안 법적 고지 배너 (dev-07-01 후속, #463) */}
      <div className="flex items-start gap-3 rounded-2xl border border-warning-soft-border bg-warning-soft-bg p-4 text-warning-soft-fg">
        <span className="text-xl">⚠️</span>
        <div className="text-sm">
          <p className="font-semibold">{AI_DRAFT_WARNING_TITLE}</p>
          <p className="mt-0.5 opacity-90">{AI_DRAFT_WARNING}</p>
        </div>
      </div>

      {/* Report Status Card */}
      <div className="rounded-3xl border border-border bg-surface p-6">
        <div className="mb-4 flex items-center gap-2">
          <div className="h-3 w-3 rounded-full bg-primary" />
          <h2 className="text-lg font-semibold text-text-default">보고서 생성 결과</h2>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="rounded-2xl border border-border bg-surface-muted p-4">
            <div className="mb-2 text-sm text-text-muted">상태</div>
            <div className="text-lg font-bold text-text-default">
              {report.status === 'DRAFT' ? '초안' : '최종본'}
            </div>
          </div>

          <div className="rounded-2xl border border-border bg-surface-muted p-4">
            <div className="mb-2 text-sm text-text-muted">생성일시</div>
            <div className="text-lg font-bold text-text-default">
              {new Date(report.createdAt).toLocaleString('ko-KR')}
            </div>
          </div>

          {inspectionData && (
            <div className="rounded-2xl border border-border bg-surface-muted p-4">
              <div className="mb-2 text-sm text-text-muted">검수 완료율</div>
              <div className="text-lg font-bold text-text-default">{Math.round(progressPercent)}%</div>
            </div>
          )}

          {inspectionData && (
            <div className="rounded-2xl border border-border bg-surface-muted p-4">
              <div className="mb-2 text-sm text-text-muted">총 하자 수</div>
              <div className="text-lg font-bold text-text-default">{inspectionData.totalCount}</div>
            </div>
          )}
        </div>

        {inspectionData && (
          <div className="mt-6 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-text-muted">검수 진행률</span>
              <span className="text-sm font-semibold text-text-default">
                {inspectionData.reviewedCount} / {inspectionData.totalCount}
              </span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-border">
              <div
                className="h-full bg-primary transition-all duration-300"
                style={{ width: `${progressPercent}%` }}
              />
            </div>
          </div>
        )}

        {Object.keys(defectDistribution).length > 0 && (
          <div className="mt-6">
            <div className="mb-4 text-sm font-medium text-text-default">하자 등급 분포</div>
            <div className="flex gap-3">
              {['A', 'B', 'C', 'D', 'E'].map((grade) => (
                <div key={grade} className="flex flex-1 flex-col gap-1">
                  <div className="rounded-lg border border-border bg-surface-muted p-2 text-center">
                    <div className="text-sm font-bold text-text-default">{grade}</div>
                  </div>
                  <div className="text-center text-xs text-text-muted">
                    {defectDistribution[grade] || 0}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {report.groundingCheckPassed !== null && report.groundingCheckPassed !== undefined && (
          <div className="mt-6 rounded-lg bg-info-soft-bg p-3">
            <div className="text-sm text-info-soft-fg">
              {report.groundingCheckPassed ? '✓ 검증 완료' : '⚠ 검증 실패 — 내용을 확인 후 다시 검증하세요.'}
            </div>
          </div>
        )}
      </div>

      {isFinalized && (
        <div className="rounded-lg bg-info-soft-bg p-3 text-sm text-info-soft-fg">
          이 보고서는 확정되어 더 이상 편집할 수 없습니다.
          {report.pdfUrl && (
            <>
              {' '}
              <Link to={`/reports/${report.id}?mode=export`} className="underline">
                PDF 보기
              </Link>
            </>
          )}
        </div>
      )}

      {content && (
        <ReportContentEditor
          content={content}
          onChange={setContent}
          readOnly={isFinalized || isSaving || isRechecking || isFinalizing}
        />
      )}

      {!isFinalized && (
        <div className="flex flex-col gap-3 rounded-3xl border border-border bg-surface p-6">
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={handleSave} variant="primary" disabled={!dirty || isSaving}>
              {isSaving ? '저장 중...' : '저장'}
            </Button>
            <Button
              onClick={handleGroundingRecheck}
              variant="secondary"
              disabled={dirty || isRechecking}
            >
              {isRechecking ? '검증 중...' : '확정 검증'}
            </Button>
            <Button
              onClick={handleGeneratePdfAndFinalize}
              variant="primary"
              disabled={!canFinalize || isFinalizing}
            >
              {isFinalizing ? 'PDF 생성/확정 중...' : 'PDF 생성 후 확정'}
            </Button>
          </div>
          {dirty && (
            <p className="text-xs text-text-muted">
              저장하지 않은 변경 사항이 있습니다. 확정 검증 전에 저장하세요.
            </p>
          )}
          {report.groundingCheckPassed !== true && !dirty && (
            <p className="text-xs text-text-muted">
              확정 검증을 통과해야 PDF 생성 및 확정이 가능합니다.
            </p>
          )}
          {saveError && <p className="text-sm text-red-600">{saveError}</p>}
          {recheckError && <p className="text-sm text-red-600">{recheckError}</p>}
          {finalizeError && <p className="text-sm text-red-600">{finalizeError}</p>}
        </div>
      )}
    </div>
  );
}
