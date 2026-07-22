// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GradeDistributionCard } from './GradeDistributionCard';

const mockUseGradeDistribution = vi.fn();
vi.mock('../hooks/useGradeDistribution', () => ({
  useGradeDistribution: () => mockUseGradeDistribution(),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('GradeDistributionCard', () => {
  it('percent=0인 등급도 라벨이 화면에 표시된다(#565 P2 회귀 방지)', () => {
    mockUseGradeDistribution.mockReturnValue({
      data: [
        { grade: 'A', percent: 70 },
        { grade: 'B', percent: 0 },
        { grade: 'C', percent: 0 },
        { grade: 'D', percent: 0 },
        { grade: 'E', percent: 30 },
      ],
      isLoading: false,
      isError: false,
    });

    render(<GradeDistributionCard />);

    expect(screen.getByText('B 등급 (0%)')).not.toBeNull();
    expect(screen.getByText('C 등급 (0%)')).not.toBeNull();
    expect(screen.getByText('D 등급 (0%)')).not.toBeNull();
  });
});
