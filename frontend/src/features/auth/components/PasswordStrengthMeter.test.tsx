// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PasswordStrengthMeter } from './PasswordStrengthMeter';

afterEach(cleanup);

describe('PasswordStrengthMeter', () => {
  it('strength가 null이면 아무것도 렌더하지 않는다', () => {
    const { container } = render(<PasswordStrengthMeter strength={null} />);
    expect(container.firstChild).toBeNull();
  });

  it('weak이면 "위험" 라벨을 노출한다', () => {
    render(<PasswordStrengthMeter strength="weak" />);
    expect(screen.queryByText(/위험/)).not.toBeNull();
  });

  it('medium이면 "보통" 라벨을 노출한다', () => {
    render(<PasswordStrengthMeter strength="medium" />);
    expect(screen.queryByText(/보통/)).not.toBeNull();
  });

  it('strong이면 "안전" 라벨을 노출한다', () => {
    render(<PasswordStrengthMeter strength="strong" />);
    expect(screen.queryByText(/안전/)).not.toBeNull();
  });
});
