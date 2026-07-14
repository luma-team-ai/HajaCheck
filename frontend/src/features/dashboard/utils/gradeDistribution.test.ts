import { describe, it, expect } from 'vitest';
import {
  getGradeColor,
  isGradeTotalValid,
  sortGradeDistribution,
  sumGradePercent,
} from './gradeDistribution';
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

describe('isGradeTotalValid (DASH-01 V2)', () => {
  const full: GradeDistributionItem[] = [
    { grade: 'A', percent: 45 },
    { grade: 'B', percent: 25 },
    { grade: 'C', percent: 15 },
    { grade: 'D', percent: 10 },
    { grade: 'E', percent: 5 },
  ];

  it('합계가 정확히 100%면 유효하다', () => {
    expect(isGradeTotalValid(full)).toBe(true);
  });

  it('부동소수 오차 범위(< 0.5) 내면 유효하다', () => {
    // 33.33 * 3 = 99.99 → |99.99 - 100| = 0.01 < 0.5
    const items: GradeDistributionItem[] = [
      { grade: 'A', percent: 33.33 },
      { grade: 'B', percent: 33.33 },
      { grade: 'C', percent: 33.33 },
    ];
    expect(isGradeTotalValid(items)).toBe(true);
  });

  it('오차 범위를 벗어나면(합계 90%) 유효하지 않다', () => {
    const items: GradeDistributionItem[] = [
      { grade: 'A', percent: 45 },
      { grade: 'B', percent: 25 },
      { grade: 'C', percent: 15 },
      { grade: 'D', percent: 5 },
    ];
    expect(sumGradePercent(items)).toBe(90);
    expect(isGradeTotalValid(items)).toBe(false);
  });

  it('빈 배열은 검증 대상이 없어 유효하다', () => {
    expect(isGradeTotalValid([])).toBe(true);
  });

  it('허용 오차를 인자로 조절할 수 있다', () => {
    const items: GradeDistributionItem[] = [{ grade: 'A', percent: 98 }]; // 합계 98 → |−2|
    expect(isGradeTotalValid(items, 1)).toBe(false); // 오차 1 초과
    expect(isGradeTotalValid(items, 3)).toBe(true); // 오차 3 이내
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
