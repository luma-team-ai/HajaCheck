import { describe, expect, it } from 'vitest';
import { hasApplicableInspectionFilters, toInspectionFilters } from './inspectionNlSearch';

describe('inspectionNlSearch', () => {
  it('NL 응답의 하자 축과 점검 축을 목록 필터 필드로 명시 매핑한다', () => {
    expect(
      toInspectionFilters({
        type: ['CRACK'],
        grade: ['D', 'E'],
        status: ['CONFIRMED'],
        confidenceMin: null,
        inspectionType: ['REGULAR'],
        inspectionStatus: ['REVIEWED', 'REPORTED'],
        inspectionDateFrom: '2026-05-28',
        inspectionDateTo: '2026-07-28',
        roundNoMin: 1,
        roundNoMax: 1,
        defectCountMin: 2,
        defectCountMax: 5,
      }),
    ).toEqual({
      defectType: ['CRACK'],
      defectGrade: ['D', 'E'],
      defectStatus: ['CONFIRMED'],
      inspectionType: ['REGULAR'],
      inspectionStatus: ['REVIEWED', 'REPORTED'],
      inspectionDateFrom: '2026-05-28',
      inspectionDateTo: '2026-07-28',
      roundNoMin: 1,
      roundNoMax: 1,
      defectCountMin: 2,
      defectCountMax: 5,
    });
  });

  it('빈 배열·null·빈 문자열은 undefined 키가 아니라 필터 객체에서 제거한다', () => {
    const filters = toInspectionFilters({
      type: [],
      grade: [],
      status: [],
      confidenceMin: null,
      inspectionType: null,
      inspectionStatus: [],
      inspectionDateFrom: '',
      inspectionDateTo: null,
      roundNoMin: null,
      roundNoMax: null,
      defectCountMin: null,
      defectCountMax: null,
    });

    expect(filters).toEqual({});
    expect(hasApplicableInspectionFilters(filters)).toBe(false);
  });
});
