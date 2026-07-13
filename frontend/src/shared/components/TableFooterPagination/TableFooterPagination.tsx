import type { ChangeEvent } from 'react';
import { Pagination } from '../Pagination/Pagination';
import './TableFooterPagination.css';

interface TableFooterPaginationProps {
  pageSize: number;
  pageSizeOptions?: number[];
  onPageSizeChange: (size: number) => void;
  currentPage: number;
  totalPages: number;
  totalItems: number;
  onPageChange: (page: number) => void;
}

// Figma node-id 322-2363 "6. FOOTER (Pagination)" 기준 — 테이블/목록 하단에 붙는
// 페이지 사이즈 선택 + 범위 텍스트 + Pagination 조합. Pagination은 공통 컴포넌트 재사용
export function TableFooterPagination({
  pageSize,
  pageSizeOptions = [10, 20, 50],
  onPageSizeChange,
  currentPage,
  totalPages,
  totalItems,
  onPageChange,
}: TableFooterPaginationProps) {
  const rangeStart = totalItems === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const rangeEnd = Math.min(currentPage * pageSize, totalItems);

  function handlePageSizeChange(event: ChangeEvent<HTMLSelectElement>) {
    onPageSizeChange(Number(event.target.value));
  }

  return (
    <div className="table-footer-pagination">
      <div className="table-footer-pagination-meta">
        <span className="table-footer-pagination-label">페이지당</span>
        <select
          className="table-footer-pagination-select"
          value={pageSize}
          onChange={handlePageSizeChange}
          aria-label="페이지당 항목 수"
        >
          {pageSizeOptions.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
        <span className="table-footer-pagination-range">
          {rangeStart}-{rangeEnd} / {totalItems}
        </span>
      </div>

      <Pagination currentPage={currentPage} totalPages={totalPages} onPageChange={onPageChange} />
    </div>
  );
}
