// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AIErrorFallback } from './AIErrorFallback';

afterEach(cleanup);

describe('AIErrorFallback', () => {
  it('표준 문구를 렌더링한다', () => {
    render(<AIErrorFallback />);

    expect(
      screen.getByText('AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.'),
    ).not.toBeNull();
  });

  it('재시도 버튼 클릭 시 onRetry가 호출된다', () => {
    const handleRetry = vi.fn();
    render(<AIErrorFallback onRetry={handleRetry} />);

    fireEvent.click(screen.getByText('다시 시도'));

    expect(handleRetry).toHaveBeenCalledTimes(1);
  });
});
