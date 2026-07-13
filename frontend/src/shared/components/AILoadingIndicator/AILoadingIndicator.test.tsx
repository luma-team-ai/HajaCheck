// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AILoadingIndicator } from './AILoadingIndicator';

afterEach(cleanup);

describe('AILoadingIndicator', () => {
  it('기본 렌더 시 role=status와 기본 안내 문구를 표시한다', () => {
    render(<AILoadingIndicator />);

    expect(screen.getByRole('status')).not.toBeNull();
    expect(screen.getByText('AI 분석 중입니다...')).not.toBeNull();
  });

  it('label prop을 전달하면 해당 문구를 표시한다', () => {
    render(<AILoadingIndicator label="보고서 생성 중..." />);

    expect(screen.getByText('보고서 생성 중...')).not.toBeNull();
  });
});
