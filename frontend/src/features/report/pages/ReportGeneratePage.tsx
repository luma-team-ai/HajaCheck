import { useCallback, useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { useInspectionResult } from '../../inspection/hooks/useInspectionResult';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { reportApi } from '../api/reportApi';
import type { ReportDetailResponse } from '../api/reportApi';
import { ReportContentEditor } from '../components/ReportContentEditor';
import { ReportEditorHero } from '../components/editor/ReportEditorHero';
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

function formatElapsedTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  if (diffMs < 60000) return '방금 전';
  if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}분 전`;
  if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}시간 전`;
  return `${Math.floor(diffMs / 86400000)}일 전`;
}

// Figma 시안 §4 — 보고서 작성 단계 A→E. 활성 조건은 핸드오프 §4 참조.
interface StepContext {
  isFinalized: boolean;
  hasContent: boolean;
  groundingCheckPassed: boolean | null | undefined;
  dirty: boolean;
  hasPdf: boolean;
}

const REPORT_STEPS: ReadonlyArray<{ key: string; label: string; isActive: (ctx: StepContext) => boolean }> = [
  { key: 'A', label: 'AI 분류', isActive: (ctx) => ctx.hasContent },
  { key: 'B', label: '작성자 확인', isActive: (ctx) => ctx.groundingCheckPassed === true || ctx.dirty },
  { key: 'C', label: '발행', isActive: (ctx) => ctx.isFinalized && ctx.hasPdf },
];

const PDF_VIEWER_FRAGMENT = 'toolbar=0&navpanes=0&view=FitH';

function normalizePdfPreviewUrl(pdfUrl: string): string {
  const trimmed = pdfUrl.trim();
  const candidate = /^localhost(?::\d+)?\//i.test(trimmed)
    ? `${window.location.protocol}//${trimmed}`
    : trimmed;

  try {
    const url = new URL(candidate, window.location.origin);
    if (url.pathname.startsWith('/api/reports/')) {
      return `${url.pathname}${url.search}`;
    }
    return url.href;
  } catch {
    return candidate;
  }
}

function buildPdfPreviewSrc(pdfUrl: string, blobUrl?: string | null): string {
  const targetUrl = blobUrl || normalizePdfPreviewUrl(pdfUrl);
  const hashIndex = targetUrl.indexOf('#');
  const baseUrl = hashIndex >= 0 ? targetUrl.slice(0, hashIndex) : targetUrl;
  return `${baseUrl}#${PDF_VIEWER_FRAGMENT}`;
}

function shouldPreflightPdf(pdfUrl: string): boolean {
  try {
    const url = new URL(normalizePdfPreviewUrl(pdfUrl), window.location.origin);
    return url.origin === window.location.origin && url.pathname.startsWith('/api/reports/');
  } catch {
    return false;
  }
}

export function ReportGeneratePage() {
  const { reportId: routeReportId } = useParams<{ reportId?: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const reportIdParam = routeReportId;
  const parsedReportId = Number(reportIdParam);
  const hasValidReportId = Number.isInteger(parsedReportId) && parsedReportId > 0;
  const isExportMode = searchParams.get('mode') === 'export';

  const [report, setReport] = useState<ReportDetailResponse | null>(null);
  const [content, setContent] = useState<ReportContent | null>(null);
  const [savedContent, setSavedContent] = useState<ReportContent | null>(null);

  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [isRechecking, setIsRechecking] = useState(false);
  const [recheckError, setRecheckError] = useState<string | null>(null);
  const [isFinalizing, setIsFinalizing] = useState(false);
  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const [isDownloadingPdf, setIsDownloadingPdf] = useState(false);
  const [pdfPreviewKey, setPdfPreviewKey] = useState(0);
  const [pdfLoadError, setPdfLoadError] = useState<string | null>(null);
  const [isPdfChecking, setIsPdfChecking] = useState(false);
  const inspectionId = report?.inspectionId ?? 0;
  const setActiveReportId = useInspectionStore((state) => state.setActiveReportId);

  useEffect(() => {
    if (hasValidReportId) {
      setActiveReportId(parsedReportId);
    }
  }, [parsedReportId, hasValidReportId, setActiveReportId]);

  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);

  const verifyPdfPreview = useCallback(async (pdfUrl: string, signal?: AbortSignal) => {
    setPdfLoadError(null);

    if (!shouldPreflightPdf(pdfUrl)) {
      setPdfPreviewKey((current) => current + 1);
      return;
    }

    setIsPdfChecking(true);
    try {
      const requestUrl = normalizePdfPreviewUrl(pdfUrl);
      const response = await fetch(requestUrl, {
        method: 'GET',
        credentials: 'include',
        cache: 'no-store',
        signal,
      });
      if (!response.ok) throw new Error(`PDF 응답 오류 (${response.status})`);
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      setPdfBlobUrl((prevUrl) => {
        if (prevUrl) URL.revokeObjectURL(prevUrl);
        return objectUrl;
      });
      setPdfPreviewKey((current) => current + 1);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      setPdfLoadError(extractErrorMessage(err, 'PDF를 불러올 수 없습니다.'));
    } finally {
      setIsPdfChecking(false);
    }
  }, []);

  useEffect(() => {
    setPdfLoadError(null);
    if (!report?.pdfUrl || !isExportMode) return;

    const controller = new AbortController();
    void verifyPdfPreview(report.pdfUrl, controller.signal);
    return () => controller.abort();
  }, [isExportMode, report?.pdfUrl, verifyPdfPreview]);


  const { data: inspectionData, isLoading: isInspectionLoading } = useInspectionResult(inspectionId);
  const defectImageUrls = useMemo(
    () => inspectionData?.defects.map((defect) => defect.imageUrl) ?? [],
    [inspectionData?.defects],
  );

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
      const pdfBlob = await exportReportToPdf(content, {
        facilityName: inspectionData?.facilityName,
        inspectionRound: inspectionData?.roundNo,
        issuedAt: new Date(report.createdAt),
        defectImages: inspectionData?.defects.flatMap((defect) =>
          defect.thumbnailUrl ? [{ defectType: defect.type, imageUrl: defect.thumbnailUrl }] : [],
        ),
      });
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

  const handleDownloadStoredPdf = async () => {
    if (!report?.pdfUrl || isDownloadingPdf) return;
    setIsDownloadingPdf(true);
    setFinalizeError(null);
    try {
      const response = await fetch(normalizePdfPreviewUrl(report.pdfUrl), { credentials: 'include' });
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
      setFinalizeError(extractErrorMessage(err, 'PDF 다운로드에 실패했습니다.'));
    } finally {
      setIsDownloadingPdf(false);
    }
  };

  const handleBackToViewer = () => {
    if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
      navigate('/reports');
      return;
    }
    navigate(`/inspections/${inspectionId}/viewer`);
  };

  if (!hasValidReportId) {
    return (
      <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 보고서 ID를 확인하세요.</div>
    );
  }

  if (reportQuery.isLoading || (report && inspectionId > 0 && isInspectionLoading)) {
    return <AILoadingIndicator message="보고서를 불러오는 중입니다..." />;
  }

  if (reportQuery.isError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <AIErrorFallback onRetry={() => void reportQuery.refetch()} />
        <Button onClick={handleBackToViewer} variant="secondary">
          분석 화면으로 돌아가기
        </Button>
      </div>
    );
  }

  if (!report) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6">
        <p className="text-text-muted">보고서를 찾을 수 없습니다.</p>
        <Button onClick={handleBackToViewer} variant="secondary">
          분석 화면으로 돌아가기
        </Button>
      </div>
    );
  }

  const progressPercent =
    inspectionData && inspectionData.totalCount > 0
      ? (inspectionData.reviewedCount / inspectionData.totalCount) * 100
      : 0;

  const canFinalize = report.groundingCheckPassed === true && !dirty && !isFinalized;
  const reportStepViews = REPORT_STEPS.map((step) => ({
    key: step.key,
    label: step.label,
    active: step.isActive({
      isFinalized,
      hasContent: Boolean(content),
      groundingCheckPassed: report.groundingCheckPassed,
      dirty,
      hasPdf: Boolean(report.pdfUrl),
    }),
  }));

  if (isExportMode) {
    return (
      <div className="flex h-[calc(100vh-80px)] min-h-0 flex-col overflow-hidden bg-surface-muted">
        <div className="flex items-center justify-between border-b border-border bg-surface/70 px-6 py-2 backdrop-blur-[10px]">
          <div className="flex items-center gap-2 text-base font-medium text-text-default">
            <svg width="20" height="17" viewBox="0 0 24 20" fill="none" aria-hidden>
              <path d="M6.5 16.5a4.5 4.5 0 0 1-.6-8.96A5.5 5.5 0 0 1 16.2 5.9 4.75 4.75 0 0 1 17.5 15h-.4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M9 10.5l2.5 2.5 5-5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span>자동 저장됨 · {formatElapsedTime(report.createdAt)}</span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              onClick={() => void handleDownloadStoredPdf()}
              variant="primary"
              disabled={isDownloadingPdf || !report.pdfUrl}
            >
              {isDownloadingPdf ? '내보내는 중...' : 'PDF 내보내기'}
              <svg className="ml-1 h-4 w-4 text-surface" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
              </svg>
            </Button>
          </div>
        </div>
        <div className="flex min-h-0 flex-1 overflow-hidden bg-surface-sunken px-6 py-5">
          {report.pdfUrl && pdfLoadError ? (
            <div className="mx-auto my-6 flex w-full max-w-[860px] flex-col items-center justify-center gap-4 rounded-lg border border-border bg-surface p-8 text-center shadow-sm">
              <p className="text-lg font-semibold text-text-default">PDF를 불러올 수 없습니다.</p>
              <p className="text-sm text-text-muted">{pdfLoadError}</p>
              <Button onClick={() => void handleDownloadStoredPdf()} variant="secondary">
                PDF 내보내기 시도
              </Button>
            </div>
          ) : report.pdfUrl && !isPdfChecking ? (
            <div className="mx-auto h-full w-full max-w-[860px] overflow-hidden bg-surface shadow-sm">
              <iframe
                key={pdfPreviewKey}
                title="저장된 보고서 PDF"
                src={buildPdfPreviewSrc(report.pdfUrl, pdfBlobUrl)}
                className="block h-full w-full border-0 bg-surface"
                onErrorCapture={() => setPdfLoadError('저장된 PDF URL을 브라우저에서 직접 열 수 없습니다.')}
              />
            </div>
          ) : report.pdfUrl && !pdfLoadError ? (
            <div className="flex flex-1 items-center justify-center">
              <AILoadingIndicator message="PDF를 불러오는 중..." />
            </div>
          ) : (
            <div className="mx-auto my-6 flex w-full max-w-[860px] flex-col items-center justify-center gap-3 rounded-lg bg-surface p-8 text-center shadow-sm">
              <div className="flex max-w-md flex-col gap-3">
                <p className="text-lg font-semibold text-text-default">저장된 PDF가 없습니다.</p>
                <p className="text-sm text-text-muted">
                  편집 화면에서 grounding 검증을 통과한 뒤 PDF 생성 및 확정을 먼저 완료하세요.
                </p>
              </div>
            </div>
          )}
        </div>
        {finalizeError && <p className="m-0 bg-surface px-6 py-2 text-sm text-danger">{finalizeError}</p>}
      </div>
    );
  }

  return (
    <div className="min-h-full bg-surface-muted px-6 py-6 lg:px-8 lg:py-8">
      <div className="mx-auto flex w-full max-w-[1024px] flex-col gap-6">
        <ReportEditorHero
          reportId={report.id}
          createdAt={report.createdAt}
          isFinalized={isFinalized}
          progressPercent={progressPercent}
          reviewedCount={inspectionData?.reviewedCount}
          totalCount={inspectionData?.totalCount}
          defectCount={content?.summary.total_count ?? 0}
          steps={reportStepViews}
          canFinalize={canFinalize}
          isFinalizing={isFinalizing}
          onFinalize={() => void handleGeneratePdfAndFinalize()}
        />

      {/* grounding 검증 실패 상태 — 통과 완료 표시는 상단 단계/확정 버튼 상태로만 드러낸다. */}
      {report.groundingCheckPassed === false && (
        <div className="flex items-center gap-3 rounded-lg border border-warning-soft-border bg-warning-soft-bg p-4 text-warning-soft-fg text-sm">
          <svg className="h-5 w-5 shrink-0 text-warning-soft-fg" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M10 2.4 18 17H2L10 2.4Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
            <path d="M10 7v4.2M10 14.2v.1" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          <span className="font-medium">검증 실패 — 내용을 확인 후 다시 검증하세요.</span>
        </div>
      )}

      {/* 확정 완료 메시지 — "이 보고서는 확정되어 더 이상 편집할 수 없습니다." 텍스트 보존(테스트 의존) */}
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

      {/* 5-8. 보고서 본문 에디터 (개요/요약 결론/상세 내역/조치 권고) */}
      {content && (
        <ReportContentEditor
          content={content}
          onChange={setContent}
          readOnly={isFinalized || isSaving || isRechecking || isFinalizing}
          defectImageUrls={defectImageUrls}
        />
      )}

      {/* 9. 하단 저장/검증 상태 바 */}
      {!isFinalized && (
        <div className="flex flex-col gap-3 rounded-lg border border-border bg-surface p-6">
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
    </div>
  );
}
