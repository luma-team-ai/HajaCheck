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

  it('percent>0 항목은 shrink 가능해야 총 폭이 100%를 넘지 않는다(#580 P2 회귀 방지)', () => {
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

    // 0%(고정 40px) 항목만 shrink-0이어야 하고, percent>0 항목은 shrink 가능해야
    // flex 컨테이너가 0%항목의 고정폭만큼 나머지를 비례 축소해 총합 100%를 유지한다.
    const aLabel = screen.getByText('A 등급 (70%)').closest('li');
    const bLabel = screen.getByText('B 등급 (0%)').closest('li');
    expect(aLabel?.className).not.toContain('shrink-0');
    expect(bLabel?.className).toContain('shrink-0');
  });
});
