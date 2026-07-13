import './Pagination.css';

interface PaginationProps {
  currentPage: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}

const DOTS = 'dots' as const;
type PageEntry = number | typeof DOTS;

function range(start: number, end: number): number[] {
  const length = end - start + 1;
  return Array.from({ length }, (_, index) => start + index);
}

// Figma 시안(node-id 322-2363)처럼 페이지가 많을 때 1 2 3 ... 5 형태로 생략 표시
function getPageEntries(currentPage: number, totalPages: number, siblingCount = 1): PageEntry[] {
  const totalPageNumbers = siblingCount * 2 + 5;

  if (totalPageNumbers >= totalPages) {
    return range(1, totalPages);
  }

  const leftSiblingIndex = Math.max(currentPage - siblingCount, 1);
  const rightSiblingIndex = Math.min(currentPage + siblingCount, totalPages);

  const shouldShowLeftDots = leftSiblingIndex > 2;
  const shouldShowRightDots = rightSiblingIndex < totalPages - 2;

  if (!shouldShowLeftDots && shouldShowRightDots) {
    const leftItemCount = 3 + 2 * siblingCount;
    return [...range(1, leftItemCount), DOTS, totalPages];
  }

  if (shouldShowLeftDots && !shouldShowRightDots) {
    const rightItemCount = 3 + 2 * siblingCount;
    return [1, DOTS, ...range(totalPages - rightItemCount + 1, totalPages)];
  }

  return [1, DOTS, ...range(leftSiblingIndex, rightSiblingIndex), DOTS, totalPages];
}

export function Pagination({ currentPage, totalPages, onPageChange }: PaginationProps) {
  const pageEntries = getPageEntries(currentPage, totalPages);

  function handlePageClick(page: number) {
    if (page === currentPage) {
      return;
    }
    onPageChange(page);
  }

  return (
    <nav className="pagination" aria-label="페이지 네비게이션">
      <button
        type="button"
        className="pagination-nav"
        disabled={currentPage <= 1}
        onClick={() => handlePageClick(currentPage - 1)}
        aria-label="이전 페이지"
      >
        ‹
      </button>

      {pageEntries.map((entry, index) =>
        entry === DOTS ? (
          // 생략(...) 표시는 실제 데이터가 아닌 순수 구분자라 서버 id가 없음 — index를 key로 사용
          <span key={`dots-${index}`} className="pagination-dots" aria-hidden="true">
            ...
          </span>
        ) : (
          <button
            key={entry}
            type="button"
            className={`pagination-page${entry === currentPage ? ' pagination-page--active' : ''}`}
            aria-current={entry === currentPage ? 'page' : undefined}
            onClick={() => handlePageClick(entry)}
          >
            {entry}
          </button>
        ),
      )}

      <button
        type="button"
        className="pagination-nav"
        disabled={currentPage >= totalPages}
        onClick={() => handlePageClick(currentPage + 1)}
        aria-label="다음 페이지"
      >
        ›
      </button>
    </nav>
  );
}
