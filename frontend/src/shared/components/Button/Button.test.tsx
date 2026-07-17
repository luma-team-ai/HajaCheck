// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Button } from './Button';

afterEach(cleanup);

describe('Button', () => {
  it('클릭 시 onClick이 1회 호출된다', () => {
    const handleClick = vi.fn();
    render(<Button onClick={handleClick}>확인</Button>);

    fireEvent.click(screen.getByText('확인'));

    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('disabled 상태에서는 클릭해도 onClick이 호출되지 않는다', () => {
    const handleClick = vi.fn();
    render(
      <Button disabled onClick={handleClick}>
        비활성
      </Button>,
    );

    fireEvent.click(screen.getByText('비활성'));

    expect(handleClick).not.toHaveBeenCalled();
  });
});
