// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InspectionCycleStatusBadge } from './InspectionCycleStatusBadge';
import { INSPECTION_CYCLE_COLOR_CLASS } from '../inspectionCycleColors';

const TODAY = new Date(2026, 6, 20);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(TODAY);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('InspectionCycleStatusBadge', () => {
  it('초과 상태면 D+n 라벨과 overdue 클래스를 렌더링한다', () => {
    render(<InspectionCycleStatusBadge nextInspectionDueAt="2026-07-17" />);

    const badge = screen.getByText('D+3');
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.overdueBadgeBg);
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.overdueBadgeText);
  });

  it('임박 상태(7일 이내)면 D-n 라벨과 upcoming 클래스를 렌더링한다', () => {
    render(<InspectionCycleStatusBadge nextInspectionDueAt="2026-07-25" />);

    const badge = screen.getByText('D-5');
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.upcomingBadgeBg);
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.upcomingBadgeText);
  });

  it('여유이내 상태(60일 이내)면 D-n 라벨과 grace 클래스를 렌더링한다', () => {
    render(<InspectionCycleStatusBadge nextInspectionDueAt="2026-08-20" />);

    const badge = screen.getByText('D-31');
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.graceBadgeBg);
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.graceBadgeText);
  });

  it('여유 상태(61일 초과)면 "여유" 라벨과 safe 클래스를 렌더링한다', () => {
    render(<InspectionCycleStatusBadge nextInspectionDueAt="2026-12-21" />);

    const badge = screen.getByText('여유');
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.safeBadgeBg);
    expect(badge.className).toContain(INSPECTION_CYCLE_COLOR_CLASS.safeBadgeText);
  });

  it('다음점검일이 없으면 여유(safe)로 표시한다', () => {
    render(<InspectionCycleStatusBadge nextInspectionDueAt={null} />);

    expect(screen.getByText('여유')).toBeTruthy();
  });
});
