import { useState } from "react";
import { Button } from "../../../shared/components/Button";
import { TableFooterPagination } from "../../../shared/components/TableFooterPagination";
import { DefectFilterBar } from "../components/DefectFilterBar";
import { DefectTable } from "../components/DefectTable";
import { useDefects } from "../hooks/useDefects";
import type { DefectListFilters } from "../types";
import "./DefectListPage.css";

const DEFAULT_SIZE = 10;

// 하자 목록 — HAJA-30. FacilityListPage(features/facility)와 동일하게 목록 조회 훅 + 테이블 +
// (신규) 필터·페이지네이션 조합으로 구성한다. AppShellRoute 자식(셸 포함) — /defects/:id(상세)와
// 동일한 셸 아래 목록→상세 이동 흐름을 유지한다.
export function DefectListPage() {
  const [filters, setFilters] = useState<DefectListFilters>({
    page: 0,
    size: DEFAULT_SIZE,
  });
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
    <section className="defect-list-page" aria-labelledby="defect-list-title">
      <header className="defect-list-page__header">
        <nav
          className="defect-list-page__breadcrumb"
          aria-label="하자 관리 현재 위치"
        >
          <span>HajaCheck</span>
          <span aria-hidden="true">›</span>
          <span className="defect-list-page__breadcrumb-current">
            하자 관리
          </span>
        </nav>

        <div className="defect-list-page__title-row">
          <div className="defect-list-page__title-group">
            <h1 id="defect-list-title">하자 관리</h1>
            <span className="defect-list-page__count">
              총 {totalElements.toLocaleString()}건
            </span>
          </div>

          <div
            className="defect-list-page__actions"
            aria-label="하자 목록 작업"
          >
            <Button
              variant="secondary"
              size="md"
              title="내보내기 기능 연동 예정"
            >
              내보내기
            </Button>
            <Button
              variant="primary"
              size="md"
              title="보고서 생성 기능 연동 예정"
            >
              보고서 생성
            </Button>
          </div>
        </div>

        <DefectFilterBar filters={filters} onChange={setFilters} />
      </header>

      <div className="defect-list-page__table-region">
        <div className="defect-list-page__table-scroll">
          <DefectTable
            defects={data?.content}
            isLoading={isLoading}
            isError={isError}
            onRetry={refetch}
          />
        </div>

        {!isLoading && !isError && (
          <div className="defect-list-page__pagination">
            <TableFooterPagination
              pageSize={size}
              onPageSizeChange={handlePageSizeChange}
              currentPage={currentPage}
              totalPages={totalPages}
              totalItems={totalElements}
              onPageChange={handlePageChange}
            />
          </div>
        )}
      </div>
    </section>
  );
}
