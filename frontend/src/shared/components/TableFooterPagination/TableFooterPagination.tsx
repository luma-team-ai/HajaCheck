import { useEffect, useRef, useState } from 'react';
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

  const [isPageSizeOpen, setIsPageSizeOpen] = useState(false);
  const pageSizeRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleOutsideClick = (event: MouseEvent) => {
      if (pageSizeRef.current && !pageSizeRef.current.contains(event.target as Node)) setIsPageSizeOpen(false);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsPageSizeOpen(false);
    };
    document.addEventListener('mousedown', handleOutsideClick);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleOutsideClick);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  return (
    // mb-20: 페이지네이션(다음 페이지 버튼 등)이 화면 우하단 고정 퀵상담 FAB(bottom-8 + h-14)에
    // 가려지거나 클릭이 막히지 않도록 여유 확보 — 이 컴포넌트를 쓰는 목록 페이지에만 적용(#499,
    // 전체 페이지 일괄 padding 대신 실제로 겹치는 지점에만 스코프)
    <div className="mb-20 flex flex-wrap items-center justify-between gap-4 border-t border-border bg-surface px-6 pt-[17px] pb-4">
      <div className="flex items-center gap-2 text-xs text-text-muted">
        <span>페이지당</span>
        <div ref={pageSizeRef} className="relative">
          <button
            type="button"
            onClick={() => setIsPageSizeOpen((previous) => !previous)}
            aria-haspopup="listbox"
            aria-expanded={isPageSizeOpen}
            aria-label="페이지당 항목 수"
            className="inline-flex min-w-14 cursor-pointer items-center justify-between gap-2 rounded-full border border-border bg-surface-muted px-3 py-1.5 text-xs font-medium text-heading"
          >
            <span>{pageSize}</span>
            <svg className={`h-3 w-3 text-text-muted transition-transform ${isPageSizeOpen ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" d="m19 9-7 7-7-7" />
            </svg>
          </button>
          {isPageSizeOpen && (
            <div role="listbox" aria-label="페이지당 항목 수 목록" className="absolute bottom-full left-0 z-20 mb-1.5 flex w-full min-w-14 flex-col gap-0.5 rounded-2xl border border-border bg-surface-muted p-1.5 shadow-lg">
              {pageSizeOptions.map((option) => (
                <button
                  key={option}
                  type="button"
                  role="option"
                  aria-selected={pageSize === option}
                  onClick={() => {
                    onPageSizeChange(option);
                    setIsPageSizeOpen(false);
                  }}
                  className={`rounded-xl border-none px-2.5 py-1.5 text-left text-xs font-medium ${pageSize === option ? 'bg-surface text-heading' : 'bg-transparent text-text-muted hover:bg-surface'}`}
                >
                  {option}
                </button>
              ))}
            </div>
          )}
        </div>
        <span>
          {rangeStart}-{rangeEnd} / {totalItems}
        </span>
      </div>

      <Pagination
        currentPage={currentPage}
        totalPages={totalPages}
        onPageChange={onPageChange}
      />
    </div>
  );
}
