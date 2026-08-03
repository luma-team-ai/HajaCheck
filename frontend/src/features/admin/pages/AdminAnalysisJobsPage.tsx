import { useRef, useState } from 'react';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination/TableFooterPagination';
import { AdminAnalysisJobTable } from '../components/AdminAnalysisJobTable';
import type { FilterValue } from '../components/AdminUserFilterBar';
import { DEFAULT_PAGE_SIZE } from '../constants';
import { useAdminAnalysisJobs } from '../hooks/useAdminAnalysisJobs';
import type { AdminAnalysisJobStatus } from '../analysisJobTypes';

const STATUS_FILTER_OPTIONS: { value: FilterValue<AdminAnalysisJobStatus>; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'PENDING', label: '대기' },
  { value: 'ANALYZING', label: '진행중' },
  { value: 'FAILED', label: '실패' },
  { value: 'COMPLETED', label: '완료' },
];

// 관리자 > AI 분석 현황 모니터링(신규) — 같은 회사 소속 검사자들의 AI 분석 작업이 지금 진행
// 중인지/완료됐는지 관리자가 한눈에 본다. 헤더(브레드크럼)·사이드바는 AppShellRoute → AppLayout이
// 담당하므로 이 페이지는 CONTENT 영역만 그린다(AdminUsersPage와 동일 구조).
export function AdminAnalysisJobsPage() {
  const [status, setStatus] = useState<FilterValue<AdminAnalysisJobStatus>>('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // 필터가 바뀌면 1페이지로 되돌린다 — AdminUsersPage와 동일 이유(필터를 좁혀 결과가 1페이지뿐이
  // 되면 빈 화면이 뜨는 것 방지). 렌더 중 동기 조정(컨벤션 §5).
  const filterSignature = `${status}|${pageSize}`;
  const prevFilterSignatureRef = useRef(filterSignature);
  if (prevFilterSignatureRef.current !== filterSignature) {
    prevFilterSignatureRef.current = filterSignature;
    setPage(1);
  }

  const params = { page, size: pageSize, ...(status ? { status } : {}) };
  const { data, isLoading, isError, refetch } = useAdminAnalysisJobs(params);

  const jobs = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / pageSize));

  return (
    <div className="flex min-h-full flex-col gap-6 bg-surface-muted p-6 sm:p-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="m-0 text-2xl font-bold text-heading">AI 분석 현황</h1>
      </div>

      <div className="flex items-center gap-2" role="tablist" aria-label="상태 필터">
        {STATUS_FILTER_OPTIONS.map((option) => (
          <button
            key={option.value || 'ALL'}
            type="button"
            role="tab"
            aria-selected={status === option.value}
            className={`cursor-pointer rounded-full border px-4 py-1.5 text-[13px] font-medium ${
              status === option.value
                ? 'border-primary bg-primary text-white'
                : 'border-border bg-surface text-text-default hover:text-primary'
            }`}
            onClick={() => setStatus(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>

      <div className="overflow-hidden rounded-[20px] border border-border bg-surface">
        <AdminAnalysisJobTable
          jobs={jobs}
          isLoading={isLoading}
          isError={isError}
          onRetry={() => void refetch()}
        />
        <TableFooterPagination
          pageSize={pageSize}
          onPageSizeChange={setPageSize}
          currentPage={page}
          totalPages={totalPages}
          totalItems={totalElements}
          onPageChange={setPage}
        />
      </div>
    </div>
  );
}
