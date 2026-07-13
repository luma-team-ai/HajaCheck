import { describe, it, expect } from 'vitest';
import { getGradeColor, sortGradeDistribution, sumGradePercent } from './gradeDistribution';
import type { GradeDistributionItem } from '../types';

describe('sortGradeDistribution', () => {
  it('임의 순서로 들어온 등급을 A→E 순서로 정렬한다', () => {
    const items: GradeDistributionItem[] = [
      { grade: 'D', percent: 10 },
      { grade: 'A', percent: 45 },
      { grade: 'E', percent: 5 },
      { grade: 'B', percent: 25 },
      { grade: 'C', percent: 15 },
    ];
    const result = sortGradeDistribution(items);
    expect(result.map((item) => item.grade)).toEqual(['A', 'B', 'C', 'D', 'E']);
  });

  it('원본 배열을 변경하지 않는다', () => {
    const items: GradeDistributionItem[] = [
      { grade: 'B', percent: 25 },
      { grade: 'A', percent: 45 },
    ];
    const original = [...items];
    sortGradeDistribution(items);
    expect(items).toEqual(original);
  });
});

describe('sumGradePercent', () => {
  it('전체 등급 퍼센트 합계를 계산한다', () => {
    const items: GradeDistributionItem[] = [
      { grade: 'A', percent: 45 },
      { grade: 'B', percent: 25 },
      { grade: 'C', percent: 15 },
      { grade: 'D', percent: 10 },
      { grade: 'E', percent: 5 },
    ];
    expect(sumGradePercent(items)).toBe(100);
  });

  it('빈 배열은 0을 반환한다', () => {
    expect(sumGradePercent([])).toBe(0);
  });
});

describe('getGradeColor', () => {
  it('모든 등급에 대해 색상 hex 값을 반환한다', () => {
    const grades: GradeDistributionItem['grade'][] = ['A', 'B', 'C', 'D', 'E'];
    grades.forEach((grade) => {
      expect(getGradeColor(grade)).toMatch(/^#[0-9a-f]{6}$/i);
    });
  });
});
