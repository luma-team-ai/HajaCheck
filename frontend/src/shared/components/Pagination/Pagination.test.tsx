// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Pagination } from './Pagination';

afterEach(cleanup);

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
});
