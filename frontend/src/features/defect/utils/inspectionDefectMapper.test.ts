import { describe, expect, expectTypeOf, it } from 'vitest';
import type { InspectionDefect, InspectionDefectResponse } from '../types';
import { mapInspectionDefect } from './inspectionDefectMapper';

const rawDefect: InspectionDefectResponse = {
  id: 1,
  inspectionId: 101,
  type: 'CRACK',
  typeLabel: '균열',
  grade: 'C',
  status: 'CONFIRMED',
  confidence: 0.91,
  isReviewed: true,
  bboxX: 0.1,
  bboxY: 0.2,
  bboxW: 0.3,
  bboxH: 0.4,
  crackWidthMm: 0.25,
  crackLengthMm: 18,
  areaRatio: 0.12,
  areaMm2: 3300.4,
  areaMm2ReferenceGrade: 'B',
  mediaId: 901,
  imageUrl: '/api/media/901/thumbnail',
  detailUrl: '/api/media/901/detail',
  createdAt: '2026-08-03T10:00:00Z',
};

describe('mapInspectionDefect', () => {
  it('실제 DefectDetailItem의 isReviewed를 내부 reviewed로 변환하고 나머지 필드를 보존한다', () => {
    const result = mapInspectionDefect(rawDefect);

    expectTypeOf(result).toEqualTypeOf<InspectionDefect>();
    expect(result.reviewed).toBe(true);
    expect('isReviewed' in result).toBe(false);
    expect(result.detailUrl).toBe('/api/media/901/detail');
    expect(result.areaRatio).toBe(0.12);
    expect(result.areaMm2).toBe(3300.4); // #1658/#1669 — areaRatio와 별개로 보존되는지 확인
    expect(result.areaMm2ReferenceGrade).toBe('B'); // #1683/#1684 — areaMm2와 마찬가지로 보존되는지 확인
  });
});
