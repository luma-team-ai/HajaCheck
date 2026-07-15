// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ErrorFallback } from './ErrorFallback';

afterEach(cleanup);

describe('ErrorFallback', () => {
  it('onRetry 전달 시 재시도 버튼 클릭하면 onRetry가 호출된다', () => {
    const handleRetry = vi.fn();
    render(<ErrorFallback onRetry={handleRetry} />);

    fireEvent.click(screen.getByText('다시 시도'));

    expect(handleRetry).toHaveBeenCalledTimes(1);
  });

  it('onRetry가 없으면 재시도 버튼이 렌더되지 않는다', () => {
    render(<ErrorFallback />);

    expect(screen.queryByText('다시 시도')).toBeNull();
  });
});
