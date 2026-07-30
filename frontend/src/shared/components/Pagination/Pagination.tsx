interface PaginationProps {
  currentPage: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}

export function getPaginationItems(currentPage: number, totalPages: number): (number | '...')[] {
  if (totalPages <= 1) return [1];

  const delta = 1;
  const range: number[] = [];

  for (
    let i = Math.max(2, currentPage - delta);
    i <= Math.min(totalPages - 1, currentPage + delta);
    i++
  ) {
    range.push(i);
  }

  if (currentPage <= 2 && totalPages >= 3) {
    if (!range.includes(2)) range.push(2);
    if (!range.includes(3) && totalPages >= 4) range.push(3);
  }

  if (currentPage >= totalPages - 1 && totalPages >= 3) {
    if (!range.includes(totalPages - 2) && totalPages - 2 > 1) range.unshift(totalPages - 2);
    if (!range.includes(totalPages - 1) && totalPages - 1 > 1) range.unshift(totalPages - 1);
  }

  range.sort((a, b) => a - b);

  const items: (number | '...')[] = [1];

  if (range.length > 0) {
    if (range[0] > 2) {
      items.push('...');
    }
    items.push(...range);
    if (range[range.length - 1] < totalPages - 1) {
      items.push('...');
    }
  } else if (totalPages > 2) {
    items.push('...');
  }

  if (totalPages > 1) {
    items.push(totalPages);
  }

  return items;
}

const BUTTON_BASE =
  'inline-flex h-8 w-8 items-center justify-center rounded-full text-xs font-medium transition-colors duration-150 border-none';

// Figma "FOOTER (Pagination)" 디자인 스펙 준수 — 현재 페이지 활성 검은색 원형 뱃지,
// 페이지 번호 버튼, 줄임표(...), 이전/다음 화살표 이동 컨트롤을 제공한다.
export function Pagination({ currentPage, totalPages, onPageChange }: PaginationProps) {
  const items = getPaginationItems(currentPage, totalPages);

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
        className={`${BUTTON_BASE} bg-transparent text-zinc-600 enabled:hover:bg-zinc-100 disabled:cursor-not-allowed disabled:opacity-40 cursor-pointer`}
        disabled={currentPage <= 1}
        onClick={handlePrev}
        aria-label="이전 페이지"
      >
        ‹
      </button>

      {items.map((item, index) => {
        if (item === '...') {
          return (
            <span
              key={`ellipsis-${index}`}
              className="inline-flex h-8 w-8 items-center justify-center text-xs text-zinc-400"
            >
              ...
            </span>
          );
        }

        const isCurrent = item === currentPage;
        return (
          <button
            key={item}
            type="button"
            className={`${BUTTON_BASE} ${
              isCurrent
                ? 'bg-zinc-900 text-white cursor-default'
                : 'bg-transparent text-zinc-700 hover:bg-zinc-100 cursor-pointer'
            }`}
            aria-current={isCurrent ? 'page' : undefined}
            aria-label={`${item}페이지`}
            onClick={() => {
              if (!isCurrent) {
                onPageChange(item);
              }
            }}
          >
            {item}
          </button>
        );
      })}

      <button
        type="button"
        className={`${BUTTON_BASE} bg-transparent text-zinc-600 enabled:hover:bg-zinc-100 disabled:cursor-not-allowed disabled:opacity-40 cursor-pointer`}
        disabled={currentPage >= totalPages}
        onClick={handleNext}
        aria-label="다음 페이지"
      >
        ›
      </button>
    </nav>
  );
}
