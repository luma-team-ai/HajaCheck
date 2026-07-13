import './Pagination.css';

interface PaginationProps {
  currentPage: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}

export function Pagination({ currentPage, totalPages, onPageChange }: PaginationProps) {
  const pages = Array.from({ length: totalPages }, (_, index) => index + 1);

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
        이전
      </button>

      {pages.map((page) => (
        <button
          key={page}
          type="button"
          className={`pagination-page${page === currentPage ? ' pagination-page--active' : ''}`}
          aria-current={page === currentPage ? 'page' : undefined}
          onClick={() => handlePageClick(page)}
        >
          {page}
        </button>
      ))}

      <button
        type="button"
        className="pagination-nav"
        disabled={currentPage >= totalPages}
        onClick={() => handlePageClick(currentPage + 1)}
        aria-label="다음 페이지"
      >
        다음
      </button>
    </nav>
  );
}
