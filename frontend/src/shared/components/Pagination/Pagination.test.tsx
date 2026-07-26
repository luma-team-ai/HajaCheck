// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getPaginationItems, Pagination } from './Pagination';

afterEach(cleanup);

describe('getPaginationItems', () => {
  it('totalPages가 5이고 currentPage가 1일 때 [1, 2, 3, "...", 5]를 반환한다', () => {
    expect(getPaginationItems(1, 5)).toEqual([1, 2, 3, '...', 5]);
  });

  it('totalPages가 10이고 currentPage가 5일 때 [1, "...", 4, 5, 6, "...", 10]를 반환한다', () => {
    expect(getPaginationItems(5, 10)).toEqual([1, '...', 4, 5, 6, '...', 10]);
  });
});

describe('Pagination', () => {
  it('다음 버튼 클릭 시 onPageChange(currentPage + 1)이 호출된다', () => {
    const handlePageChange = vi.fn();
    render(<Pagination currentPage={2} totalPages={5} onPageChange={handlePageChange} />);

    fireEvent.click(screen.getByLabelText('다음 페이지'));

    expect(handlePageChange).toHaveBeenCalledWith(3);
  });

  it('첫 페이지에서는 이전 버튼이 disabled 된다', () => {
    render(<Pagination currentPage={1} totalPages={5} onPageChange={vi.fn()} />);

    expect(screen.getByLabelText('이전 페이지').hasAttribute('disabled')).toBe(true);
  });

  it('마지막 페이지에서는 다음 버튼이 disabled 된다', () => {
    render(<Pagination currentPage={5} totalPages={5} onPageChange={vi.fn()} />);

    expect(screen.getByLabelText('다음 페이지').hasAttribute('disabled')).toBe(true);
  });

  it('페이지 번호 클릭 시 onPageChange(page)가 호출된다', () => {
    const handlePageChange = vi.fn();
    render(<Pagination currentPage={1} totalPages={5} onPageChange={handlePageChange} />);

    fireEvent.click(screen.getByLabelText('3페이지'));

    expect(handlePageChange).toHaveBeenCalledWith(3);
  });

  it('현재 페이지 번호 버튼에는 aria-current="page"가 설정된다', () => {
    render(<Pagination currentPage={1} totalPages={5} onPageChange={vi.fn()} />);

    const currentButton = screen.getByLabelText('1페이지');
    expect(currentButton.getAttribute('aria-current')).toBe('page');
  });
});
