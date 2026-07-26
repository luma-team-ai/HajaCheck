import { useMemo, useState } from 'react';
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
// 내보내기(PDF)할 수 있다. BE 미구현이라 MSW 목 기준으로 우선 개발한다(contract 확정 전까지 유지).
export function ReportListPage() {
  const [filters, setFilters] = useState<ReportListFilters>({ page: 0, size: DEFAULT_PAGE_SIZE });
  const [selectedIds, setSelectedIds] = useState<Set<number>>(() => new Set());
  const [activeReport, setActiveReport] = useState<ReportListItem | null>(null);

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

  // 완료(FINALIZED) 상태 + PDF가 있는 선택 건만 각각 새 탭으로 연다 — 단건 PDF 링크(ReportGeneratePage)와
  // 동일한 방식(axios blob 다운로드 대신 세션 쿠키 인증 브라우저 네비게이션)을 그대로 재사용한다.
  function handleBulkExport() {
    exportableRows.forEach((row) => {
      if (row.pdfUrl) {
        window.open(row.pdfUrl, '_blank', 'noopener');
      }
    });
  }

  return (
    <div className="flex min-h-full flex-col gap-5 bg-surface-muted p-6">
      <nav className="flex items-center gap-2 text-sm">
        <span className="font-medium text-neutral-600">보고서</span>
        <span className="text-neutral-600">›</span>
        <span className="font-medium text-zinc-900">보고서 목록 / 이력 관리</span>
      </nav>

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
                disabled={exportableRows.length === 0}
                onClick={handleBulkExport}
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
                <span>내보내기(일괄){exportableRows.length > 0 ? ` (${exportableRows.length})` : ''}</span>
              </button>
            </div>
          </div>

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

            <ReportVersionHistoryPanel activeReport={activeReport} onClose={() => setActiveReport(null)} />
          </div>
        </div>
      </div>
    </div>
  );
}
