interface PaginationProps {
  currentPage: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}

const PAGE_BASE =
  'inline-flex h-8 w-8 items-center justify-center rounded-full border-none bg-transparent text-sm font-medium text-primary cursor-pointer transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-40 enabled:hover:bg-surface-muted';

// 페이지 번호 목록 없이 이전/다음 화살표만 노출하는 단순 페이지네이션 — 범위 텍스트("1-10 / 1,284")는
// TableFooterPagination이 별도로 표시하므로 여기서는 이동 컨트롤만 책임진다.
export function Pagination({ currentPage, totalPages, onPageChange }: PaginationProps) {
  function handlePrev() {
    if (currentPage > 1) {
      onPageChange(currentPage - 1);
    }
  }

  function handleNext() {
    if (currentPage < totalPages) {
      onPageChange(currentPage + 1);
    }
  }

  return (
    <nav className="flex items-center gap-1" aria-label="페이지 네비게이션">
      <button
        type="button"
        className={PAGE_BASE}
        disabled={currentPage <= 1}
        onClick={handlePrev}
        aria-label="이전 페이지"
      >
        ‹
      </button>
      <button
        type="button"
        className={PAGE_BASE}
        disabled={currentPage >= totalPages}
        onClick={handleNext}
        aria-label="다음 페이지"
      >
        ›
      </button>
    </nav>
  );
}
