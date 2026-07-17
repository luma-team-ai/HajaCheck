// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { GradeBadge } from './GradeBadge';
import { DASHBOARD_COLOR_CLASS, GRADE_BG_CLASS } from '../colors';

afterEach(cleanup);

describe('GradeBadge', () => {
  it('등급이 있으면 등급 라벨과 해당 등급 배경색 클래스를 렌더링한다', () => {
    render(<GradeBadge grade="A" />);

    const badge = screen.getByText('A');
    expect(badge.className).toContain(GRADE_BG_CLASS.A);
    expect(badge.className).not.toContain(DASHBOARD_COLOR_CLASS.gradeUnknownBg);
  });

  it('모든 등급(A~E)에 대해 라벨과 등급별 배경색 클래스가 대응된다', () => {
    const grades = ['A', 'B', 'C', 'D', 'E'] as const;

    grades.forEach((grade) => {
      cleanup();
      render(<GradeBadge grade={grade} />);

      const badge = screen.getByText(grade);
      expect(badge.className).toContain(GRADE_BG_CLASS[grade]);
    });
  });

  // BE PendingPriorityResponse.grade는 AI 등급 미분류 하자에서 null로 내려온다(HAJA-17 dev-03-01)
  it('등급이 null이면 "-" 라벨과 미분류 배경색 클래스를 렌더링한다', () => {
    render(<GradeBadge grade={null} />);

    const badge = screen.getByText('-');
    expect(badge.className).toContain(DASHBOARD_COLOR_CLASS.gradeUnknownBg);
  });

  it('등급이 null이면 등급별 배경색 클래스를 사용하지 않는다', () => {
    render(<GradeBadge grade={null} />);

    const badge = screen.getByText('-');
    Object.values(GRADE_BG_CLASS).forEach((gradeClass) => {
      expect(badge.className).not.toContain(gradeClass);
    });
  });
});
