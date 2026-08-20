// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BottomNavBarFab } from './BottomNavBarFab';

afterEach(cleanup);

describe('BottomNavBarFab', () => {
  it('클릭 시 onClick이 호출된다', () => {
    const handleClick = vi.fn();
    render(<BottomNavBarFab onClick={handleClick} />);

    fireEvent.click(screen.getByLabelText('상담 챗봇 열기'));

    expect(handleClick).toHaveBeenCalledTimes(1);
  });
});
