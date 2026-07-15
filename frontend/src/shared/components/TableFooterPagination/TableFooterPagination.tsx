import type { ChangeEvent } from 'react';
import { Pagination } from '../Pagination/Pagination';

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
    <div className="flex flex-wrap items-center justify-between gap-4 border-t border-border bg-surface px-6 pt-[17px] pb-4">
      <div className="flex items-center gap-2 text-xs text-text-muted">
        <span>페이지당</span>
        <select
          className="cursor-pointer rounded-2xl border border-border bg-surface-muted px-[9px] py-[5px] text-xs text-[#1c1b1c]"
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
        <span>
          {rangeStart}-{rangeEnd} / {totalItems}
        </span>
      </div>

      <Pagination currentPage={currentPage} totalPages={totalPages} onPageChange={onPageChange} />
    </div>
  );
}
