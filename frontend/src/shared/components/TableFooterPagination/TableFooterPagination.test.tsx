// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TableFooterPagination } from './TableFooterPagination';

afterEach(cleanup);

describe('TableFooterPagination', () => {
  it('현재 페이지 기준 범위 텍스트를 표시한다', () => {
    render(
      <TableFooterPagination
        pageSize={10}
        onPageSizeChange={vi.fn()}
        currentPage={1}
        totalPages={5}
        totalItems={47}
        onPageChange={vi.fn()}
      />,
    );

    expect(screen.getByText('1-10 / 47')).not.toBeNull();
  });

  it('페이지 사이즈 선택 시 onPageSizeChange가 호출된다', () => {
    const handlePageSizeChange = vi.fn();
    render(
      <TableFooterPagination
        pageSize={10}
        onPageSizeChange={handlePageSizeChange}
        currentPage={1}
        totalPages={5}
        totalItems={47}
        onPageChange={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText('페이지당 항목 수'), { target: { value: '20' } });

    expect(handlePageSizeChange).toHaveBeenCalledWith(20);
  });

  it('Pagination이 함께 렌더되어 페이지 이동 시 onPageChange가 호출된다', () => {
    const handlePageChange = vi.fn();
    render(
      <TableFooterPagination
        pageSize={10}
        onPageSizeChange={vi.fn()}
        currentPage={2}
        totalPages={5}
        totalItems={47}
        onPageChange={handlePageChange}
      />,
    );

    fireEvent.click(screen.getByLabelText('다음 페이지'));

    expect(handlePageChange).toHaveBeenCalledWith(3);
  });
});
