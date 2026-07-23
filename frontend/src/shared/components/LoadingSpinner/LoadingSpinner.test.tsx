// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { LoadingSpinner } from './LoadingSpinner';

afterEach(cleanup);

describe('LoadingSpinner', () => {
  it('기본 메시지("불러오는 중...")를 role=status로 렌더링한다', () => {
    render(<LoadingSpinner />);

    const status = screen.getByRole('status');
    expect(status.textContent).toBe('불러오는 중...');
  });

  it('message prop을 지정하면 그 텍스트를 대신 보여준다', () => {
    render(<LoadingSpinner message="목록을 불러오는 중..." />);

    expect(screen.getByRole('status').textContent).toBe('목록을 불러오는 중...');
  });
});
