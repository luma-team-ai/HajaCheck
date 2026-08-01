import { useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { reportApi } from '../api/reportApi';
import { useCompanyReports } from '../hooks/useCompanyReports';
import { useCompanyReportsSummary } from '../hooks/useCompanyReportsSummary';
import { ReportListFilterBar } from '../components/ReportListFilterBar';
import { ReportListKpiBar } from '../components/ReportListKpiBar';
import { ReportListTable } from '../components/ReportListTable';
import { ReportVersionHistoryPanel } from '../components/ReportVersionHistoryPanel';
import { isReportContent } from '../types';
import type { ReportListFilters, ReportListItem } from '../types';
import { buildReportPdfFileName, exportReportToPdf } from '../utils/exportReportToPdf';
import { buildReportPdfContext } from '../utils/reportPdfContext';
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
  // 삭제 확인 모달용 — 삭제 버튼 클릭 시 대상 row를 보관하고 Modal로 확인한다
  const [pendingDeleteRow, setPendingDeleteRow] = useState<ReportListItem | null>(null);

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

  function actionErrorMessage(type: 'clone' | 'submit' | 'delete'): string {
    if (type === 'clone') return '보고서를 복사하지 못했습니다.';
    if (type === 'submit') return '보고서를 발행하지 못했습니다. 보고서 내용과 검증 상태를 확인해 주세요.';
    return '보고서 초안을 삭제하지 못했습니다.';
  }

  async function handleCloneReport(row: ReportListItem) {
    if (pendingAction) return;
    setPendingAction({ reportId: row.id, type: 'clone' });
    setActionErrors((prev) => ({ ...prev, [row.id]: undefined }));
    try {
      const response = await reportApi.cloneReport(row.id);
      await refreshReportQueries(response.data.inspectionId);
      navigate(`/reports/${response.data.id}`);
    } catch {
      setActionErrors((prev) => ({ ...prev, [row.id]: actionErrorMessage('clone') }));
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
      const pdfBlob = await exportReportToPdf(
        content,
        buildReportPdfContext(
          report,
          null,
          content.reportOptions?.includePhoto !== false,
          { facilityName: row.facilityName, inspectionRound: row.roundNo },
        ),
      );
      const fileName = buildReportPdfFileName(report.inspectionId);
      const uploadResponse = await reportApi.uploadPdf(row.id, pdfBlob, fileName);
      await reportApi.finalizeReport(row.id, uploadResponse.data.pdfUrl);
      await refreshReportQueries(report.inspectionId);
    } catch {
      setActionErrors((prev) => ({ ...prev, [row.id]: actionErrorMessage('submit') }));
    } finally {
      setPendingAction(null);
    }
  }

  // 삭제 버튼 클릭 → 확인 모달 표시 (window.confirm 대체)
  function handleDeleteReport(row: ReportListItem) {
    if (pendingAction || row.status !== 'DRAFT') return;
    setPendingDeleteRow(row);
  }

  async function confirmDeleteReport() {
    if (!pendingDeleteRow) return;
    const row = pendingDeleteRow;
    setPendingDeleteRow(null);
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
    } catch {
      setActionErrors((prev) => ({ ...prev, [row.id]: actionErrorMessage('delete') }));
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

  // 진행 중 레이블 (복제 중 / 삭제 중)
  const progressLabel =
    pendingAction?.type === 'clone'
      ? '보고서를 복사하는 중입니다...'
      : pendingAction?.type === 'delete'
        ? '보고서를 삭제하는 중입니다...'
        : null;

  return (
    <div className="flex min-h-full flex-col gap-5 bg-surface-muted p-6">

      {/* 삭제 확인 모달 */}
      <Modal
        open={pendingDeleteRow !== null}
        onClose={() => setPendingDeleteRow(null)}
        title="보고서 초안 삭제"
        closeOnOverlayClick={false}
      >
        <div className="flex flex-col gap-6">
          <p className="text-sm leading-6 text-text-default">
            이 보고서 초안을 삭제하면 <span className="font-semibold text-danger">되돌릴 수 없습니다.</span>
            <br />계속하시겠습니까?
          </p>
          <div className="flex justify-end gap-3">
            <Button variant="secondary" onClick={() => setPendingDeleteRow(null)}>
              취소
            </Button>
            <Button variant="danger" onClick={() => void confirmDeleteReport()}>
              삭제
            </Button>
          </div>
        </div>
      </Modal>

      {/* 복제/삭제 진행 중 모달 */}
      <Modal
        open={progressLabel !== null}
        onClose={() => {}}
        closeOnOverlayClick={false}
      >
        <div className="flex flex-col items-center gap-4 px-2 py-2">
          <svg
            className="h-8 w-8 animate-spin text-primary"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
          </svg>
          <p className="text-sm font-medium text-heading">{progressLabel}</p>
        </div>
      </Modal>

      <div className="flex min-h-full flex-1 overflow-hidden rounded-[20px] border border-border bg-surface shadow-sm">
        <div className="flex flex-1 flex-col">
          <div className="flex items-end justify-between border-b border-border px-8 py-6">
            <div className="flex items-baseline gap-3">
              <h1 className="m-0 text-3xl font-semibold text-heading">보고서</h1>
              <span className="text-base font-medium text-text-muted">총 {totalItems}건</span>
            </div>
            <div className="flex items-center gap-3">
              <Button
                variant="secondary"
                size="md"
                disabled={exportableRows.length === 0 || isExporting}
                onClick={() => void handleBulkExport()}
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
                <span>{isExporting ? '다운로드 중…' : 'PDF 일괄 다운로드'}{exportableRows.length > 0 ? ` (${exportableRows.length})` : ''}</span>
              </Button>
            </div>
          </div>
          <div className="border-b border-border px-8 py-2 text-xs leading-5 text-text-muted">
            <p className="m-0">
              다운로드는 완료된 PDF만 가능합니다. 편집 중인 보고서는 발행을 완료한 뒤 PDF 일괄 다운로드 대상에 포함됩니다.
            </p>
            {exportMessage && <p className="m-0 mt-1 font-medium text-heading">{exportMessage}</p>}
          </div>

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
                  onDeleteReport={(row) => handleDeleteReport(row)}
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
