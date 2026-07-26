import { useMemo, useState } from 'react';
import { formatReportListTitle } from '../utils/reportListFormat';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { useCompanyReports } from '../hooks/useCompanyReports';
import { useCompanyReportsSummary } from '../hooks/useCompanyReportsSummary';
import { ReportListFilterBar } from '../components/ReportListFilterBar';
import { ReportListKpiBar } from '../components/ReportListKpiBar';
import { ReportListTable } from '../components/ReportListTable';
import { ReportVersionHistoryPanel } from '../components/ReportVersionHistoryPanel';
import type { ReportListFilters, ReportListItem } from '../types';

const DEFAULT_PAGE_SIZE = 10;

// 보고서 목록/이력 관리(#463) — 사이드바 "보고서" 최상위 메뉴 첫 항목. 회사 스코프 전체 보고서를
// 시설물/상태/기간/검색으로 필터링하고, 행 단위로 버전 이력을 확인하거나 선택 항목을 일괄
// 내보내기(PDF)할 수 있다. hybrid에서는 실 API를 우선 사용하고 미구현 목록/요약만 훅에서 폴백한다.
export function ReportListPage() {
  const [filters, setFilters] = useState<ReportListFilters>({ page: 0, size: DEFAULT_PAGE_SIZE });
  const [selectedIds, setSelectedIds] = useState<Set<number>>(() => new Set());
  const [activeReport, setActiveReport] = useState<ReportListItem | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportMessage, setExportMessage] = useState<string | null>(null);

  const summaryQuery = useCompanyReportsSummary();
  const listQuery = useCompanyReports(filters);

  const rows = useMemo(() => listQuery.data?.content ?? [], [listQuery.data]);
  const totalItems = listQuery.data?.totalElements ?? 0;
  const pageSize = filters.size ?? DEFAULT_PAGE_SIZE;
  const currentPage = (filters.page ?? 0) + 1; // TableFooterPagination은 1-based
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));

  const selectedRows = useMemo(() => rows.filter((row) => selectedIds.has(row.id)), [rows, selectedIds]);
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
    setFilters({ ...next, size: filters.size });
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
      <div className="flex overflow-hidden rounded-[20px] border border-border bg-surface shadow-sm">
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

          <div className="flex overflow-hidden">
            <div className="flex flex-1 flex-col">
              <ReportListFilterBar filters={filters} onChange={handleFiltersChange} />

              <div className="px-6 pb-2">
                <ReportListTable
                  reports={rows}
                  isLoading={listQuery.isLoading}
                  isError={listQuery.isError}
                  onRetry={() => listQuery.refetch()}
                  selectedIds={selectedIds}
                  onSelectionChange={setSelectedIds}
                  onOpenVersionHistory={setActiveReport}
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
