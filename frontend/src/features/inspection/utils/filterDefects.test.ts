import { describe, it, expect } from 'vitest';
import { filterDefects } from './filterDefects';
import type { Defect } from '../types';

describe('filterDefects', () => {
  const mockDefects: Defect[] = [
    { id: 1, type: '균열', grade: 'A', status: 'DETECTED', confidence: 0.9, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 }, widthMm: 2, lengthMm: 20, summary: '균열 요약 1' },
    { id: 2, type: '박리박락', grade: 'B', status: 'DETECTED', confidence: 0.7, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 }, areaRatio: 0.08, summary: '박리박락 요약 2' },
    { id: 3, type: '철근노출', grade: 'C', status: 'DETECTED', confidence: 0.5, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 }, areaRatio: 0.05, summary: '철근노출 요약 3' },
    { id: 4, type: '철근노출', grade: 'D', status: 'DETECTED', confidence: 0.3, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 }, areaRatio: 0.15, summary: '철근노출 요약 4' },
    { id: 5, type: '박리박락', grade: 'E', status: 'DETECTED', confidence: 0.1, bbox: { x: 0, y: 0, width: 0.1, height: 0.1 }, areaRatio: 0.02, summary: '박리박락 요약 5' },
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

  // #1395 — 등급 미판정(grade=null)은 A~E 어디에도 속하지 않아 예전엔 모든 필터 조합에서
  // 사라졌고, totalCount 분모에는 남아 "점검 요약"이 영구히 잠겼다.
  describe('등급 미판정(grade=null) 하자', () => {
    const ungraded: Defect = {
      id: 9,
      type: '균열',
      grade: null,
      status: 'DETECTED',
      confidence: 0.8,
      bbox: { x: 0, y: 0, width: 0.1, height: 0.1 },
      summary: '등급 미판정 하자',
    };

    it('등급 필터를 전부 켜도 사라지지 않는다', () => {
      const result = filterDefects([ungraded], 0, ['A', 'B', 'C', 'D', 'E']);
      expect(result).toHaveLength(1);
    });

    it('등급 필터를 전부 꺼도 남는다(등급 축으로는 거르지 않는다)', () => {
      const result = filterDefects([ungraded], 0, []);
      expect(result).toHaveLength(1);
    });

    it('신뢰도 필터는 그대로 적용된다', () => {
      expect(filterDefects([ungraded], 0.9, ['A'])).toHaveLength(0);
      expect(filterDefects([ungraded], 0.8, ['A'])).toHaveLength(1);
    });
  });
});
