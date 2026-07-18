import { useState } from 'react';
import '../../../shared/styles/layout.css';
import { TableFooterPagination } from '../../../shared/components/TableFooterPagination';
import { DefectFilterBar } from '../components/DefectFilterBar';
import { DefectTable } from '../components/DefectTable';
import { useDefects } from '../hooks/useDefects';
import type { DefectListFilters } from '../types';

const DEFAULT_SIZE = 20;

// 하자 목록 — HAJA-30. FacilityListPage(features/facility)와 동일하게 목록 조회 훅 + 테이블 +
// (신규) 필터·페이지네이션 조합으로 구성한다. AppShellRoute 자식(셸 포함) — /defects/:id(상세)와
// 동일한 셸 아래 목록→상세 이동 흐름을 유지한다.
export function DefectListPage() {
  const [filters, setFilters] = useState<DefectListFilters>({ page: 0, size: DEFAULT_SIZE });
  const { data, isLoading, isError, refetch } = useDefects(filters);

  const size = filters.size ?? DEFAULT_SIZE;
  const currentPage = (filters.page ?? 0) + 1; // TableFooterPagination/Pagination은 1-based
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / size));

  const handlePageChange = (page: number) => {
    setFilters((prev) => ({ ...prev, page: page - 1 }));
  };

  const handlePageSizeChange = (nextSize: number) => {
    setFilters((prev) => ({ ...prev, size: nextSize, page: 0 }));
  };

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">
          하자 목록
          {data && <span className="ml-2 text-base font-normal text-text-muted">{totalElements}</span>}
        </h1>
      </div>

      <div className="flex flex-col gap-4">
        <DefectFilterBar filters={filters} onChange={setFilters} />

        <div className="overflow-hidden rounded-2xl border border-border bg-surface">
          <DefectTable
            defects={data?.content}
            isLoading={isLoading}
            isError={isError}
            onRetry={refetch}
          />

          {!isLoading && !isError && (
            <TableFooterPagination
              pageSize={size}
              onPageSizeChange={handlePageSizeChange}
              currentPage={currentPage}
              totalPages={totalPages}
              totalItems={totalElements}
              onPageChange={handlePageChange}
            />
          )}
        </div>
      </div>
    </div>
  );
}
