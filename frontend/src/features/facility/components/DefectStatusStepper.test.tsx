// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DefectStatusStepper } from './DefectStatusStepper';

afterEach(cleanup);

describe('DefectStatusStepper', () => {
  it('현재 상태 라벨을 강조 표시(aria-current=step)한다', () => {
    render(<DefectStatusStepper status="CONFIRMED" />);

    const current = screen.getByText('검수확정').previousSibling as HTMLElement;
    expect(current.getAttribute('aria-current')).toBe('step');
  });

  it('4단계 라벨을 순서대로 모두 렌더링한다', () => {
    render(<DefectStatusStepper status="DETECTED" />);

    ['신규', '검수확정', '조치중', '조치완료'].forEach((label) => {
      expect(screen.getByText(label)).not.toBeNull();
    });
  });

  it('현재 단계 이전 단계는 완료 상태로, 이후 단계는 미완료 상태로 표시된다', () => {
    render(<DefectStatusStepper status="CONFIRMED" />);

    const completedDot = screen.getByText('검수확정').previousSibling as HTMLElement;
    const upcomingDot = screen.getByText('조치중').previousSibling as HTMLElement;

    expect(completedDot.className).toContain('bg-heading');
    expect(upcomingDot.className).toContain('bg-border');
  });
});
