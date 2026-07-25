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

  it('편중된 분포에서도 라벨이 말줄임(ellipsis) 없이 온전한 너비로 렌더링된다(2026-07-24 Figma 재정합, 세그먼트 비율폭 방식 폐기)', () => {
    // A 89.1%처럼 한 등급이 압도적이면, 예전 방식(라벨 너비=세그먼트 비율%)은 B~E 라벨 폭이
    // 몇 px로 짜부라져 text-ellipsis로 잘렸다. 지금은 라벨을 세그먼트 비율과 무관하게
    // 내용 너비 그대로 렌더링하므로 ellipsis 클래스 자체가 존재하지 않아야 한다.
    mockUseGradeDistribution.mockReturnValue({
      data: [
        { grade: 'A', percent: 89.1 },
        { grade: 'B', percent: 5 },
        { grade: 'C', percent: 3 },
        { grade: 'D', percent: 2 },
        { grade: 'E', percent: 0.9 },
      ],
      isLoading: false,
      isError: false,
    });

    render(<GradeDistributionCard />);

    expect(screen.getByText('A 등급 (89.1%)')).not.toBeNull();
    expect(screen.getByText('B 등급 (5%)')).not.toBeNull();
    expect(screen.getByText('C 등급 (3%)')).not.toBeNull();
    expect(screen.getByText('D 등급 (2%)')).not.toBeNull();
    expect(screen.getByText('E 등급 (0.9%)')).not.toBeNull();

    const bLabel = screen.getByText('B 등급 (5%)').closest('li');
    expect(bLabel?.className).not.toContain('text-ellipsis');
    expect(bLabel?.className).not.toContain('shrink-0');
    expect(bLabel?.style.width).toBe('');
  });
});
