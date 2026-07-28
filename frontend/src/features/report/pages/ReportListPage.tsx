import { useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { reportApi } from '../api/reportApi';
import { useCompanyReports } from '../hooks/useCompanyReports';
import { useCompanyReportsSummary } from '../hooks/useCompanyReportsSummary';
import { ReportListFilterBar } from '../components/ReportListFilterBar';
import { ReportListKpiBar } from '../components/ReportListKpiBar';
import { ReportListTable } from '../components/ReportListTable';
import { ReportVersionHistoryPanel } from '../components/ReportVersionHistoryPanel';
import { inspectionApi } from '../../inspection/api/inspectionApi';
import { DEFECT_TYPE_CODE_LABELS } from '../../inspection/api/inspectionApi.types';
import { isReportContent } from '../types';
import type { ReportListFilters, ReportListItem } from '../types';
import { buildReportPdfFileName, exportReportToPdf } from '../utils/exportReportToPdf';
import { formatReportListTitle } from '../utils/reportListFormat';

const DEFAULT_PAGE_SIZE = 10;

// 보고서 목록/이력 관리(#463) — 사이드바 "보고서" 최상위 메뉴 첫 항목. 회사 스코프 전체 보고서를
// 시설물/상태/기간/검색으로 필터링하고, 행 단위로 버전 이력을 확인하거나 선택 항목을 일괄
// 내보내기(PDF)할 수 있다. hybrid에서는 실 API를 우선 사용하고 미구현 목록/요약만 훅에서 폴백한다.
export function ReportListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<ReportListFilters>({ page: 0, size: DEFAULT_PAGE_SIZE });
  // 현재 페이지 rows만 보관하면 페이지를 넘기는 순간 이전 선택 항목의 PDF가
  // 일괄 내보내기 대상에서 사라진다. 선택 시 행 스냅샷을 id로 보존한다.
  const [selectedRowsById, setSelectedRowsById] = useState<Map<number, ReportListItem>>(
    () => new Map(),
  );
  const [activeReport, setActiveReport] = useState<ReportListItem | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportMessage, setExportMessage] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<{ reportId: number; type: 'clone' | 'submit' | 'delete' } | null>(null);
  const [actionErrors, setActionErrors] = useState<Record<number, string | undefined>>({});

  const summaryQuery = useCompanyReportsSummary();
  const listQuery = useCompanyReports(filters);

  const rows = useMemo(() => listQuery.data?.content ?? [], [listQuery.data]);
  const totalItems = listQuery.data?.totalElements ?? 0;
  const pageSize = filters.size ?? DEFAULT_PAGE_SIZE;
  const currentPage = (filters.page ?? 0) + 1; // TableFooterPagination은 1-based
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));

  const selectedIds = useMemo(() => new Set(selectedRowsById.keys()), [selectedRowsById]);
  const selectedRows = useMemo(() => Array.from(selectedRowsById.values()), [selectedRowsById]);
  const exportableRows = useMemo(
    () => selectedRows.filter((row) => row.status === 'FINALIZED' && row.pdfUrl),
    [selectedRows],
  );

  function handlePageChange(page: number) {
    setFilters((prev) => ({ ...prev, page: page - 1 }));
  }

  function handlePageSizeChange(size: number) {
    setFilters((prev) => ({ ...prev, size, page: 0 }));
  }

  function handleFiltersChange(next: ReportListFilters) {
    setSelectedRowsById(new Map());
    setFilters({ ...next, size: filters.size });
  }

  function handleSelectionChange(nextIds: Set<number>) {
    setSelectedRowsById((previous) => {
      const next = new Map(previous);
      rows.forEach((row) => {
        if (nextIds.has(row.id)) {
          next.set(row.id, row);
        } else {
          next.delete(row.id);
        }
      });
      return next;
    });
  }

  async function refreshReportQueries(inspectionId?: number) {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['report', 'company-list'] }),
      queryClient.invalidateQueries({ queryKey: ['report', 'company-summary'] }),
      inspectionId
        ? queryClient.invalidateQueries({ queryKey: ['report', 'version-history', inspectionId] })
        : Promise.resolve(),
    ]);
  }

  function actionErrorMessage(error: unknown): string {
    if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string') {
      return error.message;
    }
    return '처리하지 못했습니다. 다시 시도해 주세요.';
  }

  async function handleCloneReport(row: ReportListItem) {
    if (pendingAction) return;
    setPendingAction({ reportId: row.id, type: 'clone' });
    setActionErrors((prev) => ({ ...prev, [row.id]: undefined }));
    try {
      const response = await reportApi.cloneReport(row.id);
      await refreshReportQueries(response.data.inspectionId);
      navigate(`/reports/${response.data.id}`);
    } catch (error) {
      setActionErrors((prev) => ({ ...prev, [row.id]: actionErrorMessage(error) }));
    } finally {
      setPendingAction(null);
    }
  }

  async function handleSubmitReport(row: ReportListItem) {
    if (pendingAction || row.status !== 'DRAFT') return;
    setPendingAction({ reportId: row.id, type: 'submit' });
    setActionErrors((prev) => ({ ...prev, [row.id]: undefined }));
    try {
      let report = (await reportApi.getReport(row.id)).data;
      if (report.status !== 'DRAFT') {
        throw new Error('DRAFT 보고서만 제출할 수 있습니다.');
      }
      if (!isReportContent(report.content)) {
        throw new Error('보고서 본문 형식이 올바르지 않습니다.');
      }
      const content = report.content;
      if (report.groundingCheckPassed !== true) {
        report = (await reportApi.groundingRecheck(row.id)).data;
        if (report.groundingCheckPassed !== true) {
          throw new Error('근거 재검증을 통과하지 못했습니다.');
        }
      }
      let defectImages: { defectType: string; imageUrl: string }[] = [];
      try {
        const defects = await inspectionApi.getDefects(report.inspectionId);
        defectImages = defects.data.flatMap((defect) =>
          defect.imageUrl ? [{ defectType: DEFECT_TYPE_CODE_LABELS[defect.type], imageUrl: defect.imageUrl }] : [],
        );
      } catch {
        // 사진 조회 실패는 확정 흐름을 막지 않는다. PDF는 본문만으로도 유효하며 사진대지만 생략한다.
      }
      const pdfBlob = await exportReportToPdf(content, {
        facilityName: row.facilityName,
        inspectionRound: row.roundNo,
        issuedAt: new Date(report.createdAt),
        defectImages,
      });
      const fileName = buildReportPdfFileName(report.inspectionId);
      const uploadResponse = await reportApi.uploadPdf(row.id, pdfBlob, fileName);
      await reportApi.finalizeReport(row.id, uploadResponse.data.pdfUrl);
      await refreshReportQueries(report.inspectionId);
    } catch (error) {
      setActionErrors((prev) => ({ ...prev, [row.id]: actionErrorMessage(error) }));
    } finally {
      setPendingAction(null);
    }
  }

  async function handleDeleteReport(row: ReportListItem) {
    if (pendingAction || row.status !== 'DRAFT') return;
    setPendingAction({ reportId: row.id, type: 'delete' });
    setActionErrors((prev) => ({ ...prev, [row.id]: undefined }));
    try {
      await reportApi.deleteReport(row.id);
      setSelectedRowsById((previous) => {
        const next = new Map(previous);
        next.delete(row.id);
        return next;
      });
      if (activeReport?.id === row.id) {
        setActiveReport(null);
      }
      await refreshReportQueries(row.inspectionId);
    } catch (error) {
      setActionErrors((prev) => ({ ...prev, [row.id]: actionErrorMessage(error) }));
    } finally {
      setPendingAction(null);
    }
  }

  // window.open을 여러 번 호출하면 브라우저 팝업 차단에 걸리고, PDF URL을 새 탭에서 열기만 해서는
  // '내보내기'가 다운로드로 보장되지 않는다. 기존 소유권 검증 PDF GET을 세션 쿠키로 받아 파일로 저장한다.
  async function handleBulkExport() {
    if (exportableRows.length === 0 || isExporting) return;
    setIsExporting(true);
    setExportMessage(null);
    const results = await Promise.allSettled(
      exportableRows.map(async (row) => {
        if (!row.pdfUrl) return;
        const response = await fetch(row.pdfUrl, { credentials: 'include' });
        if (!response.ok) throw new Error(`PDF ${response.status}`);
        const blob = await response.blob();
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = `${formatReportListTitle(row.facilityName, row.updatedAt, row.roundNo)}.pdf`;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        URL.revokeObjectURL(objectUrl);
      }),
    );
    const failedCount = results.filter((result) => result.status === 'rejected').length;
    setExportMessage(
      failedCount === 0
        ? `${exportableRows.length}건을 내보냈습니다.`
        : `${exportableRows.length - failedCount}건 내보냄 · ${failedCount}건 실패`,
    );
    setIsExporting(false);
  }

  return (
    <div className="flex min-h-full flex-col gap-5 bg-surface-muted p-6">
      <div className="flex min-h-full flex-1 overflow-hidden rounded-[20px] border border-border bg-surface shadow-sm">
        <div className="flex flex-1 flex-col">
          <div className="flex items-end justify-between border-b border-border px-8 py-6">
            <div className="flex items-baseline gap-3">
              <h1 className="m-0 text-3xl font-semibold text-heading">보고서</h1>
              <span className="text-base font-medium text-text-muted">총 {totalItems}건</span>
            </div>
            <div className="flex items-center gap-3">
              <span className="rounded-full border border-border bg-surface-muted px-3 py-1.5 text-sm text-text-muted">
                보고서 생성은 점검 회차 상세에서 →
              </span>
              <button
                type="button"
                disabled={exportableRows.length === 0 || isExporting}
                onClick={() => void handleBulkExport()}
                className="flex items-center gap-1.5 rounded-full border border-border bg-surface px-4 py-1.5 text-sm font-medium text-heading shadow-sm disabled:cursor-not-allowed disabled:opacity-50"
                title={
                  selectedRows.length > 0 && exportableRows.length === 0
                    ? '선택한 보고서 중 완료(PDF 확정) 상태가 없습니다'
                    : undefined
                }
              >
                <svg
                  className="h-4 w-4 text-text-muted"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth="2"
                  aria-hidden="true"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                  />
                </svg>
                <span>{isExporting ? '내보내는 중…' : '내보내기(일괄)'}{exportableRows.length > 0 ? ` (${exportableRows.length})` : ''}</span>
              </button>
            </div>
          </div>
          {exportMessage && <p className="m-0 border-b border-border px-8 py-2 text-xs text-text-muted">{exportMessage}</p>}

          <ReportListKpiBar
            summary={summaryQuery.data}
            isLoading={summaryQuery.isLoading}
            isError={summaryQuery.isError}
          />

          <div className="flex min-h-0 flex-1 overflow-hidden">
            <div className="flex flex-1 flex-col">
              <ReportListFilterBar filters={filters} onChange={handleFiltersChange} />

              <div className="flex flex-1 flex-col px-6 pb-2">
                <ReportListTable
                  reports={rows}
                  isLoading={listQuery.isLoading}
                  isError={listQuery.isError}
                  onRetry={() => listQuery.refetch()}
                  selectedIds={selectedIds}
                  onSelectionChange={handleSelectionChange}
                  onOpenVersionHistory={setActiveReport}
                  onCloneReport={(row) => void handleCloneReport(row)}
                  onSubmitReport={(row) => void handleSubmitReport(row)}
                  onDeleteReport={(row) => void handleDeleteReport(row)}
                  pendingAction={pendingAction}
                  actionErrors={actionErrors}
                />
              </div>

              <TableFooterPagination
                pageSize={pageSize}
                pageSizeOptions={[10, 20, 50]}
                onPageSizeChange={handlePageSizeChange}
                currentPage={currentPage}
                totalPages={totalPages}
                totalItems={totalItems}
                onPageChange={handlePageChange}
                reserveBottomFabSpace={false}
              />
            </div>

            <ReportVersionHistoryPanel
              activeReport={activeReport}
              onClose={() => setActiveReport(null)}
              onReverted={() => {
                void listQuery.refetch();
                void summaryQuery.refetch();
              }}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
