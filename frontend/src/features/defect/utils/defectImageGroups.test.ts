import { describe, expect, it } from 'vitest';
import type { InspectionDefect } from '../types';
import {
  groupDefectsByImage,
  groupMatchesFilters,
  sortDefectImageGroups,
} from './defectImageGroups';

function defect(id: number, overrides: Partial<InspectionDefect> = {}): InspectionDefect {
  return {
    id,
    inspectionId: 1,
    type: 'CRACK',
    typeLabel: '균열',
    grade: 'C',
    status: 'CONFIRMED',
    confidence: 0.8,
    reviewed: true,
    bboxX: 0.1,
    bboxY: 0.1,
    bboxW: 0.2,
    bboxH: 0.2,
    crackWidthMm: null,
    crackLengthMm: null,
    areaRatio: null,
    mediaId: 10,
    imageUrl: '/api/media/10/thumbnail',
    detailUrl: '/api/media/10/detail',
    createdAt: '2026-08-01T00:00:00Z',
    ...overrides,
  };
}

describe('defectImageGroups', () => {
  it('같은 mediaId는 묶고 null mediaId는 개별 유지하며 DETECTED는 제외한다', () => {
    const groups = groupDefectsByImage([
      defect(1),
      defect(2, { status: 'IN_PROGRESS' }),
      defect(3, { mediaId: null, imageUrl: null }),
      defect(4, { mediaId: null, imageUrl: null }),
      defect(5, { status: 'DETECTED' }),
    ]);

    expect(groups.map((group) => [group.key, group.defects.map((item) => item.id)])).toEqual([
      ['media:10', [2, 1]],
      ['defect:3', [3]],
      ['defect:4', [4]],
    ]);
  });

  it('대표 하자는 등급, 신뢰도, 생성일, ID 내림차순으로 결정한다', () => {
    const [group] = groupDefectsByImage([
      defect(1, { grade: 'D', confidence: 0.99 }),
      defect(2, { grade: 'E', confidence: 0.7, createdAt: '2026-08-01T00:00:00Z' }),
      defect(3, { grade: 'E', confidence: 0.8, createdAt: '2026-07-01T00:00:00Z' }),
      defect(4, { grade: 'E', confidence: 0.8, createdAt: '2026-08-02T00:00:00Z' }),
      defect(5, { grade: 'E', confidence: 0.8, createdAt: '2026-08-02T00:00:00Z' }),
    ]);

    expect(group.representative.id).toBe(5);
  });

  it('한 하자가 활성 조건을 모두 만족할 때만 그룹이 필터에 일치한다', () => {
    const [group] = groupDefectsByImage([
      defect(1, { type: 'CRACK', grade: 'E', status: 'CONFIRMED' }),
      defect(2, { type: 'SPALLING', grade: 'C', status: 'IN_PROGRESS' }),
    ]);

    expect(groupMatchesFilters(group, { type: 'CRACK', grade: 'E', status: 'CONFIRMED' })).toBe(true);
    expect(groupMatchesFilters(group, { type: 'CRACK', grade: 'C', status: 'IN_PROGRESS' })).toBe(false);
  });

  it('그룹의 최신 생성일과 최고 신뢰도로 정렬한다', () => {
    const groups = groupDefectsByImage([
      defect(1, { mediaId: 10, confidence: 0.7, createdAt: '2026-08-03T00:00:00Z' }),
      defect(2, { mediaId: 20, confidence: 0.95, createdAt: '2026-08-01T00:00:00Z' }),
    ]);

    expect(sortDefectImageGroups(groups, 'createdAt-desc').map((group) => group.mediaId)).toEqual([10, 20]);
    expect(sortDefectImageGroups(groups, 'confidence-desc').map((group) => group.mediaId)).toEqual([20, 10]);
  });
});
