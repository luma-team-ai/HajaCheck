import { describe, it, expect } from 'vitest';
import { filterDefects } from './filterDefects';
import type { Defect } from '../types';

describe('filterDefects', () => {
  const mockDefects: Defect[] = [
    { id: 1, type: '균열', grade: 'A', status: '신규', confidence: 0.9, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 } },
    { id: 2, type: '박리박락', grade: 'B', status: '신규', confidence: 0.7, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 } },
    { id: 3, type: '누수백태', grade: 'C', status: '신규', confidence: 0.5, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 } },
    { id: 4, type: '철근노출', grade: 'D', status: '신규', confidence: 0.3, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 } },
    { id: 5, type: '도장손상', grade: 'E', status: '신규', confidence: 0.1, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 } },
  ];

  it('confidence 임계값 이상의 하자만 필터링한다', () => {
    const result = filterDefects(mockDefects, 0.5, ['A', 'B', 'C', 'D', 'E']);
    expect(result).toHaveLength(3);
    expect(result[0].confidence).toBe(0.9);
    expect(result[1].confidence).toBe(0.7);
    expect(result[2].confidence).toBe(0.5);
  });

  it('grade 필터에 해당하는 하자만 필터링한다', () => {
    const result = filterDefects(mockDefects, 0, ['A', 'C', 'E']);
    expect(result).toHaveLength(3);
    expect(result.map((d) => d.grade)).toEqual(['A', 'C', 'E']);
  });

  it('confidence와 grade 두 조건을 모두 만족하는 하자만 반환한다', () => {
    const result = filterDefects(mockDefects, 0.6, ['A', 'B']);
    expect(result).toHaveLength(2);
    expect(result[0].grade).toBe('A');
    expect(result[1].grade).toBe('B');
  });

  it('빈 grade 필터일 경우 빈 배열을 반환한다', () => {
    const result = filterDefects(mockDefects, 0, []);
    expect(result).toHaveLength(0);
  });

  it('모든 등급을 포함하고 임계값이 0일 때 전체 반환한다', () => {
    const result = filterDefects(mockDefects, 0, ['A', 'B', 'C', 'D', 'E']);
    expect(result).toHaveLength(5);
  });

  it('confidence 경계값 테스트 - 정확히 임계값일 때 포함된다', () => {
    const result = filterDefects(mockDefects, 0.5, ['A', 'B', 'C', 'D', 'E']);
    expect(result.some((d) => d.confidence === 0.5)).toBe(true);
  });

  it('빈 하자 목록은 빈 결과를 반환한다', () => {
    const result = filterDefects([], 0.5, ['A', 'B']);
    expect(result).toHaveLength(0);
  });
});
