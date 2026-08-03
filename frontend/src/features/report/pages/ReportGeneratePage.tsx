import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import { Link, useBlocker, useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { AlertModal, Modal } from '../../../shared/components/Modal';
import { useInspectionResult } from '../../inspection/hooks/useInspectionResult';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import { reportApi } from '../api/reportApi';
import type { ReportDetailResponse } from '../api/reportApi';
import type { Defect } from '../../inspection/types';
import { ReportContentEditor } from '../components/ReportContentEditor';
import {
  groupDefectsByMedia,
  type DefectPhotoGroup,
} from '../components/editor/DefectPhoto';
import { ReportEditorHero } from '../components/editor/ReportEditorHero';
import { isReportContent } from '../types';
import type { ReportContent } from '../types';
import { buildReportPdfFileName, exportReportToPdf } from '../utils/exportReportToPdf';
import {
  getEmptyManualSectionLabels,
  getMissingFinalReportRequiredLabels,
} from '../utils/manualSectionValidation';
import { buildReportPdfContext } from '../utils/reportPdfContext';

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

function isPdfExportLocation(pathname: string, search: string, reportId: number): boolean {
  if (pathname !== `/reports/${reportId}`) return false;
  return new URLSearchParams(search).get('mode') === 'export';
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
  const [isRechecking, setIsRechecking] = useState(false);
  const [isFinalizing, setIsFinalizing] = useState(false);
  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const [isDownloadingPdf, setIsDownloadingPdf] = useState(false);
  const [pdfPreviewKey, setPdfPreviewKey] = useState(0);
  const [pdfLoadError, setPdfLoadError] = useState<string | null>(null);
  const [isPdfChecking, setIsPdfChecking] = useState(false);
  // 임시저장/미리보기/최종확정 통합 플로우에서 공용으로 쓰는 알림 모달(#1338).
  const [alertModal, setAlertModal] = useState<{ open: boolean; title?: string; message: string }>({
    open: false,
    message: '',
  });
  const closeAlertModal = useCallback(() => setAlertModal((prev) => ({ ...prev, open: false })), []);
  const inspectionId = report?.inspectionId ?? 0;
  const setActiveReportId = useInspectionStore((state) => state.setActiveReportId);

  useEffect(() => {
    if (hasValidReportId) {
      setActiveReportId(parsedReportId);
    }
  }, [parsedReportId, hasValidReportId, setActiveReportId]);

  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
  // 확정 전 미리보기(사용자 리포트 픽스) — "PDF 미리보기" 링크는 확정 여부와 무관하게 항상
  // 노출되는데, 실제로는 report.pdfUrl(확정 시 업로드된 저장본)이 있을 때만 내용을 보여주고
  // 그 전에는 "저장된 PDF가 없습니다"만 떴다. 확정 검증을 통과한 뒤에는, 서버에 올리기 전
  // 클라이언트에서 exportReportToPdf로 즉석 렌더링해 진짜 미리보기를 제공한다.
  const [previewBlobUrl, setPreviewBlobUrl] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);

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
    // #1235 P2 픽스 — 리포트 전환(같은 라우트 패턴 안에서 reportId만 바뀌는 클라이언트 라우팅,
    // 언마운트 없음) 시 이전 리포트의 blob URL을 무조건 먼저 리셋한다. shouldPreflightPdf가
    // false를 반환하거나 새 report에 pdfUrl 자체가 없는 경우 verifyPdfPreview가 pdfBlobUrl을
    // 건드리지 않고 조기 종료해, buildPdfPreviewSrc의 `blobUrl || normalizePdfPreviewUrl(pdfUrl)`
    // 우선순위 때문에 이전 리포트의 PDF가 새 리포트 화면에 그대로 남아있던 문제를 막는다.
    setPdfBlobUrl((prevUrl) => {
      if (prevUrl) URL.revokeObjectURL(prevUrl);
      return null;
    });
    if (!report?.pdfUrl || !isExportMode) return;

    const controller = new AbortController();
    void verifyPdfPreview(report.pdfUrl, controller.signal);
    return () => controller.abort();
  }, [isExportMode, report?.pdfUrl, verifyPdfPreview]);

  // #1235 P3 픽스 — 마지막 blob URL(pdfBlobUrl/previewBlobUrl)은 새 blob으로 교체될 때만
  // revoke됐고, 컴포넌트가 언마운트될 때(다른 라우트로 이동) 정리되지 않았다. ref로 최신값을
  // 추적해 언마운트 시점에만 한 번 revoke한다(교체 시 이미 일어나는 revoke와 중복되지 않도록
  // 별도 effect로 분리).
  const pdfBlobUrlRef = useRef<string | null>(null);
  pdfBlobUrlRef.current = pdfBlobUrl;
  const previewBlobUrlRef = useRef<string | null>(null);
  previewBlobUrlRef.current = previewBlobUrl;

  useEffect(() => {
    return () => {
      if (pdfBlobUrlRef.current) URL.revokeObjectURL(pdfBlobUrlRef.current);
      if (previewBlobUrlRef.current) URL.revokeObjectURL(previewBlobUrlRef.current);
    };
  }, []);


  const { data: inspectionData, isLoading: isInspectionLoading } = useInspectionResult(inspectionId);
  // 하자 상세 항목(content.detail.items[i])과 사진(defectPhotos[i])을 defect_id로 1:1 매칭한다.
  // 예전엔 "AI가 defects와 같은 순서로 생성한다"는 가정만으로 배열 인덱스로 짝지었는데, AI가
  // 재생성 등으로 순서를 바꾸면 그 가정이 깨져 엉뚱한 하자의 사진·bbox가 표시됐다(#1379) — 점검
  // 회차 생성 플로우(DefectOverlay 등)처럼 id를 데이터에 직접 묶어 재조립하지 않는 패턴을 따른다.
  // defect_id가 없는 구버전 저장분은 기존 인덱스 매칭으로 폴백한다.
  // 항목마다 "그 하자"가 주인공이지만, 같은 사진에 찍힌 다른 하자도 흐리게 함께 보여준다(#1333).
  const defectPhotos = useMemo<DefectPhotoGroup[]>(() => {
    const defects = inspectionData?.defects ?? [];
    const byId = new Map<number, Defect>(defects.map((defect) => [defect.id, defect]));
    const siblingsByMediaId = new Map<number, Defect[]>();
    defects.forEach((defect) => {
      if (defect.mediaId == null) return;
      const bucket = siblingsByMediaId.get(defect.mediaId);
      if (bucket) bucket.push(defect);
      else siblingsByMediaId.set(defect.mediaId, [defect]);
    });
    const toGroup = (defect: Defect): DefectPhotoGroup => ({
      mediaId: defect.mediaId ?? null,
      imageUrl: defect.imageUrl,
      defects:
        defect.mediaId == null ? [defect] : (siblingsByMediaId.get(defect.mediaId) ?? [defect]),
      highlightDefectId: defect.id,
    });
    const items = content?.detail.items ?? [];
    return items.map((item, index) => {
      const matched = item.defect_id != null ? byId.get(item.defect_id) : undefined;
      const defect = item.defect_id != null ? matched : defects[index];
      return defect ? toGroup(defect) : { mediaId: null, imageUrl: null, defects: [] };
    });
  }, [inspectionData?.defects, content?.detail.items]);

  // 부위별 사진은 사진 단위 — 같은 사진의 하자를 한 장에 모아 중복 표시를 없앤다(#1333).
  const defectPhotoGroups = useMemo(
    () => groupDefectsByMedia(inspectionData?.defects ?? []),
    [inspectionData?.defects],
  );

  const applyReport = useCallback((data: ReportDetailResponse) => {
    setReport(data);
    if (isReportContent(data.content)) {
      const nextContent: ReportContent = {
        ...data.content,
        summary: {
          ...data.content.summary,
          responsible_engineer_name:
            data.content.summary.responsible_engineer_name ??
            data.context?.assignedInspector?.name ??
            '',
        },
      };
      setContent(nextContent);
      setSavedContent(nextContent);
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
  const emptyManualSectionLabels = useMemo(() => getEmptyManualSectionLabels(content), [content]);
  const hasEmptyManualSections = emptyManualSectionLabels.length > 0;
  const missingFinalRequiredLabels = useMemo(() => getMissingFinalReportRequiredLabels(content), [content]);
  const hasMissingFinalRequiredContent = missingFinalRequiredLabels.length > 0;
  const isFinalized = report?.status === 'FINALIZED';
  const [isLeavingAfterSave, setIsLeavingAfterSave] = useState(false);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (!report || !dirty || isFinalized) return false;
    const isSameLocation =
      currentLocation.pathname === nextLocation.pathname &&
      currentLocation.search === nextLocation.search &&
      currentLocation.hash === nextLocation.hash;
    if (isSameLocation) return false;
    // 같은 보고서의 편집 ↔ 미리보기(mode=export) 전환은 같은 컴포넌트가 그대로 유지돼 content가
    // 메모리에 남아있으므로 데이터 유실 위험이 없다 — 뒤로가기(미리보기 → 편집) 방향도 예외 처리한다.
    const isSameReportPage = (location: typeof currentLocation) => location.pathname === `/reports/${report.id}`;
    if (isSameReportPage(currentLocation) && isSameReportPage(nextLocation)) return false;
    return !isPdfExportLocation(nextLocation.pathname, nextLocation.search, report.id);
  });

  // 미리보기는 확정 전 편집 중인 상태를 보기 위한 기능이라, 저장 여부·확정 검증 통과 여부와
  // 무관하게 content만 있으면 현재(미저장 포함) 내용으로 즉석 렌더링한다. 서버에 저장된 최종
  // PDF(report.pdfUrl)가 있으면 그쪽이 우선이며, 이 값은 그 fallback으로만 쓰인다.
  const canRenderClientPreview = Boolean(content);
  const includeReportPhotos = content?.reportOptions?.includePhoto !== false;

  // useInspectionResult(useInspectionResultReal)은 매 렌더마다 새 data 객체를 만든다(메모이제이션
  // 없음) — 아래 effect 의존성 배열에 inspectionData를 직접 넣으면 setPreviewBlobUrl → 리렌더 →
  // inspectionData 참조 변경 → effect 재실행이 무한 반복돼 메모리 초과로 죽는다. ref로만 최신값을
  // 읽고 의존성에서는 제외한다.
  const inspectionDataRef = useRef(inspectionData);
  inspectionDataRef.current = inspectionData;

  useEffect(() => {
    setPreviewBlobUrl((prevUrl) => {
      if (prevUrl) URL.revokeObjectURL(prevUrl);
      return null;
    });
    setPreviewError(null);
    if (!isExportMode || !report || !content) return;
    if (report.pdfUrl && !pdfLoadError) return;
    if (!canRenderClientPreview) return;

    let cancelled = false;
    void (async () => {
      try {
        const latestInspectionData = inspectionDataRef.current;
        const blob = await exportReportToPdf(
          content,
          buildReportPdfContext(report, latestInspectionData, includeReportPhotos),
        );
        if (cancelled) return;
        const objectUrl = URL.createObjectURL(blob);
        setPreviewBlobUrl((prevUrl) => {
          if (prevUrl) URL.revokeObjectURL(prevUrl);
          return objectUrl;
        });
      } catch (err) {
        if (!cancelled) setPreviewError(extractErrorMessage(err, '미리보기를 만들지 못했습니다.'));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [isExportMode, report, content, pdfLoadError, canRenderClientPreview, includeReportPhotos]);

  // 임시저장 — "임시저장" 버튼(원 저장 버튼)뿐 아니라 PDF 미리보기 가드, 최종 확정 통합 플로우에서도
  // 재사용한다(#1338). 성공 여부를 반환해 호출부가 다음 단계 진행 여부를 판단할 수 있게 한다.
  const handleSave = async (): Promise<boolean> => {
    if (!report || !content || isSaving) return false;
    if (hasEmptyManualSections) {
      setAlertModal({
        open: true,
        title: '저장할 수 없습니다',
        message: `내용이 비어 있거나 필수값이 누락된 추가 섹션이 있습니다: ${emptyManualSectionLabels.join(', ')}`,
      });
      return false;
    }
    setIsSaving(true);
    try {
      const response = await reportApi.updateContent(report.id, content);
      applyReport(response.data);
      return true;
    } catch (err) {
      const message = extractErrorMessage(err, '저장에 실패했습니다.');
      setAlertModal({ open: true, title: '저장 실패', message });
      return false;
    } finally {
      setIsSaving(false);
    }
  };

  const handleCancelLeave = () => {
    blocker.reset?.();
  };

  const handleConfirmLeave = async () => {
    if (isLeavingAfterSave) return;
    setIsLeavingAfterSave(true);
    const saved = await handleSave();
    if (!saved) {
      setIsLeavingAfterSave(false);
      return;
    }
    flushSync(() => setIsLeavingAfterSave(false));
    blocker.proceed?.();
  };

  const handleGroundingRecheck = async (): Promise<{
    success: boolean;
    data?: ReportDetailResponse;
    message?: string;
  }> => {
    if (!report || isRechecking) return { success: false };
    setIsRechecking(true);
    try {
      const response = await reportApi.groundingRecheck(report.id);
      applyReport(response.data);
      return { success: true, data: response.data };
    } catch (err) {
      const message = extractErrorMessage(err, '확정 검증에 실패했습니다.');
      return { success: false, message };
    } finally {
      setIsRechecking(false);
    }
  };

  // "PDF 미리보기" 클릭 핸들러 — 미리보기는 확정 전 편집 중인 내용을 보기 위한 기능이므로
  // 저장 성공 여부와 무관하게 바로 이동한다. 임시저장 실패(예: 빈 수동 섹션)로 미리보기까지
  // 막히면 안 된다 — 이동 경로는 blocker의 임시저장 이탈 가드 대상에서도 제외되어 있다.
  const handlePreviewClick = () => {
    if (!report) return;
    navigate(`/reports/${report.id}?mode=export`);
  };

  // "최종 보고서 확정" 버튼 핸들러 — 임시저장 → 확정 검증 → PDF 생성/업로드/확정을 순차로 진행한다(#1338).
  // 각 단계 실패 시 AlertModal로 알리고 다음 단계로 넘어가지 않는다.
  const handleFinalizeAll = async () => {
    if (!report || !content || isFinalized) return;
    if (isSaving || isRechecking || isFinalizing) return;

    if (dirty) {
      const saved = await handleSave();
      if (!saved) return;
    }

    if (hasEmptyManualSections) {
      setAlertModal({
        open: true,
        title: '확정할 수 없습니다',
        message: `내용이 비어 있거나 필수값이 누락된 추가 섹션이 있습니다: ${emptyManualSectionLabels.join(', ')}`,
      });
      return;
    }

    if (hasMissingFinalRequiredContent) {
      setAlertModal({
        open: true,
        title: '확정할 수 없습니다',
        message: `최종 보고서 확정 전 필수 항목을 작성해 주세요: ${missingFinalRequiredLabels.join(', ')}`,
      });
      return;
    }

    const recheckResult = await handleGroundingRecheck();
    if (!recheckResult.success) {
      setAlertModal({
        open: true,
        title: '확정 검증 실패',
        message: recheckResult.message ?? '확정 검증에 실패했습니다.',
      });
      return;
    }

    if (recheckResult.data?.groundingCheckPassed !== true) {
      setAlertModal({
        open: true,
        title: '검증 실패',
        message: '검증 실패 — 내용을 확인 후 다시 시도하세요.',
      });
      return;
    }

    setIsFinalizing(true);
    setFinalizeError(null);
    try {
      const pdfBlob = await exportReportToPdf(
        content,
        buildReportPdfContext(report, inspectionData, includeReportPhotos),
      );
      const fileName = buildReportPdfFileName(report.inspectionId);
      const uploadResponse = await reportApi.uploadPdf(report.id, pdfBlob, fileName);
      const finalizeResponse = await reportApi.finalizeReport(report.id, uploadResponse.data.pdfUrl);
      applyReport(finalizeResponse.data);
    } catch (err) {
      const message = extractErrorMessage(err, 'PDF 생성/확정에 실패했습니다.');
      setFinalizeError(message);
      setAlertModal({ open: true, title: 'PDF 생성/확정 실패', message });
    } finally {
      setIsFinalizing(false);
    }
  };

  const handleDownloadStoredPdf = async () => {
    const currentReport = report;
    if (!currentReport || (!currentReport.pdfUrl && !previewBlobUrl) || isDownloadingPdf) return;
    setIsDownloadingPdf(true);
    setFinalizeError(null);
    try {
      let blob: Blob;
      if (previewBlobUrl) {
        const response = await fetch(previewBlobUrl);
        blob = await response.blob();
      } else {
        const response = await fetch(normalizePdfPreviewUrl(currentReport.pdfUrl!), { credentials: 'include' });
        if (!response.ok) throw new Error(`PDF ${response.status}`);
        blob = await response.blob();
      }
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = buildReportPdfFileName(currentReport.inspectionId);
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

  // 이제 버튼 하나로 저장→검증→PDF 생성/확정을 순차 진행하므로(#1338), 아직 저장/검증 전이어도
  // 클릭 가능해야 한다. 진행 중(각 단계 loading state)에는 비활성화한다.
  // 필수값 누락은 버튼을 죽여 이유를 숨기지 않고, 클릭 시 handleFinalizeAll이 AlertModal로
  // "무엇이 비었는지" 알려준다(#1341 원 설계) — hasEmptyManualSections를 canFinalize에 넣어
  // 버튼을 조용히 비활성화했던 것은 #1375/#1377에서 이 주석과 모순되게 들어간 회귀였다(#1409).
  const canFinalize = !isFinalized;
  const isFinalizeBusy = isSaving || isRechecking || isFinalizing;
  const finalizeLabel = isSaving
    ? '저장 중...'
    : isRechecking
      ? '검증 중...'
      : isFinalizing
        ? 'PDF 생성/확정 중...'
        : '최종 보고서 확정';
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
  const alertModalElement = (
    <AlertModal
      open={alertModal.open}
      title={alertModal.title}
      message={alertModal.message}
      onClose={closeAlertModal}
    />
  );
  const leaveSaveModal =
    blocker.state === 'blocked' ? (
      <Modal
        open
        onClose={handleCancelLeave}
        title="편집한 내용이 저장되지 않았습니다"
        closeOnOverlayClick={false}
      >
        <div className="flex w-80 flex-col gap-6">
          <p className="m-0 text-sm text-text-muted">
            이 페이지를 나가기 전에 변경 내용을 임시저장합니다.
          </p>
          <div className="flex justify-end gap-3">
            <Button type="button" variant="secondary" onClick={handleCancelLeave} disabled={isLeavingAfterSave}>
              취소
            </Button>
            <Button type="button" variant="primary" onClick={() => void handleConfirmLeave()} disabled={isLeavingAfterSave}>
              {isLeavingAfterSave ? '저장 중...' : '임시저장 후 나가기'}
            </Button>
          </div>
        </div>
      </Modal>
    ) : null;

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
              disabled={isDownloadingPdf || (!report.pdfUrl && !previewBlobUrl)}
            >
              <svg className="h-4 w-4 text-surface" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
              </svg>
              {isDownloadingPdf ? '내보내는 중...' : 'PDF 내보내기'}
            </Button>
          </div>
        </div>
        <div className="flex min-h-0 flex-1 overflow-hidden bg-surface-sunken px-6 py-5">
          {report.pdfUrl && !pdfLoadError && !isPdfChecking ? (
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
          ) : previewError ? (
            <div className="mx-auto my-6 flex w-full max-w-[860px] flex-col items-center justify-center gap-4 rounded-lg border border-border bg-surface p-8 text-center shadow-sm">
              <p className="text-lg font-semibold text-text-default">미리보기를 만들지 못했습니다.</p>
              <p className="text-sm text-text-muted">{previewError}</p>
            </div>
          ) : previewBlobUrl ? (
            // 저장 PDF fallback 또는 확정 전 미리보기 — 서버 저장본이 아니라 현재 content_json을
            // 클라이언트에서 같은 exporter로 다시 렌더링한 결과임을 명확히 표시한다.
            <div className="mx-auto flex h-full w-full max-w-[860px] flex-col overflow-hidden bg-surface shadow-sm">
              <p className="m-0 border-b border-warning-soft-border bg-warning-soft-bg px-4 py-2 text-xs text-warning-soft-fg">
                {pdfLoadError
                  ? `저장된 PDF를 찾지 못해 현재 보고서 내용으로 다시 렌더링했습니다. (${pdfLoadError})`
                  : '아직 확정되지 않은 미리보기입니다. 실제 발행 후 저장되는 최종 PDF와 다를 수 있습니다.'}
              </p>
              <iframe
                title="보고서 PDF 미리보기(확정 전)"
                src={buildPdfPreviewSrc(previewBlobUrl, previewBlobUrl)}
                className="block h-full w-full flex-1 border-0 bg-surface"
              />
            </div>
          ) : canRenderClientPreview ? (
            <div className="flex flex-1 items-center justify-center">
              <AILoadingIndicator message="미리보기를 만드는 중..." />
            </div>
          ) : report.pdfUrl && pdfLoadError ? (
            <div className="mx-auto my-6 flex w-full max-w-[860px] flex-col items-center justify-center gap-4 rounded-lg border border-border bg-surface p-8 text-center shadow-sm">
              <p className="text-lg font-semibold text-text-default">PDF를 불러올 수 없습니다.</p>
              <p className="text-sm text-text-muted">{pdfLoadError}</p>
              <Button onClick={() => void handleDownloadStoredPdf()} variant="secondary">
                PDF 내보내기 시도
              </Button>
            </div>
          ) : isFinalized ? (
            // 확정 완료됐지만 content_json으로도 fallback을 만들 수 없는 데이터 이상 상태.
            <div className="mx-auto my-6 flex w-full max-w-[860px] flex-col items-center justify-center gap-3 rounded-lg bg-surface p-8 text-center shadow-sm">
              <div className="flex max-w-md flex-col gap-3">
                <p className="text-lg font-semibold text-text-default">저장된 PDF가 없습니다.</p>
                <p className="text-sm text-text-muted">PDF 파일을 찾을 수 없습니다. 관리자에게 문의해 주세요.</p>
              </div>
            </div>
          ) : (
            // content 자체를 아직 불러오지 못한 예외 상태(정상 로드된 보고서라면 canRenderClientPreview가
            // content 존재 여부만 보므로 이 분기까지 오지 않는다).
            <div className="mx-auto my-6 flex w-full max-w-[860px] flex-col items-center justify-center gap-3 rounded-lg bg-surface p-8 text-center shadow-sm">
              <div className="flex max-w-md flex-col gap-3">
                <p className="text-lg font-semibold text-text-default">아직 미리 볼 수 없습니다.</p>
                <p className="text-sm text-text-muted">편집 화면으로 돌아가 다시 시도해 주세요.</p>
              </div>
            </div>
          )}
        </div>
        {finalizeError && <p className="m-0 bg-surface px-6 py-2 text-sm text-danger">{finalizeError}</p>}
        {alertModalElement}
        {leaveSaveModal}
      </div>
    );
  }

  return (
    <div className="min-h-full bg-surface-muted px-6 py-6 lg:px-8 lg:py-8">
      <div className="mx-auto flex w-full max-w-[1024px] flex-col gap-6">
        <ReportEditorHero
          createdAt={report.createdAt}
          isFinalized={isFinalized}
          progressPercent={progressPercent}
          reviewedCount={inspectionData?.reviewedCount}
          totalCount={inspectionData?.totalCount}
          defectCount={content?.summary.total_count ?? 0}
          steps={reportStepViews}
          canFinalize={canFinalize}
          isFinalizing={isFinalizeBusy}
          finalizeLabel={finalizeLabel}
          onFinalize={() => void handleFinalizeAll()}
          onPreviewClick={() => void handlePreviewClick()}
          canSave={dirty}
          isSaving={isSaving}
          onSaveClick={() => void handleSave()}
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

      {/* 5-8. 보고서 본문 에디터 (기본현황/결과 요약/진단 외관조사결과/보수ㆍ보강) */}
      {content && (
        <ReportContentEditor
          content={content}
          onChange={setContent}
          readOnly={isFinalized || isSaving || isRechecking || isFinalizing}
          defectPhotos={defectPhotos}
          defectPhotoGroups={defectPhotoGroups}
        />
      )}

      </div>
      {alertModalElement}
      {leaveSaveModal}
    </div>
  );
}
