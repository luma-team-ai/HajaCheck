import { describe, expect, it } from 'vitest';
import { isReportContent } from './types';
import { mockReportDetailResponse } from './mocks/reportDetail.mock';

describe('isReportContent', () => {
  const validContent = {
    overview: { purpose: 'a', facility_summary: 'b', scope: 'c' },
    summary: { overall_opinion: 'd', total_count: 0, count_by_grade: {}, key_findings: [] },
    detail: { items: [] },
    recommendation: { items: [], monitoring_points: [] },
  };

  it('returns true for a complete ReportContent object', () => {
    expect(isReportContent(validContent)).toBe(true);
  });

  it('실백엔드 상세 응답 fixture의 content 계약을 통과시킨다', () => {
    expect(isReportContent(mockReportDetailResponse.content)).toBe(true);
  });

  it('returns false when value is null or not an object', () => {
    expect(isReportContent(null)).toBe(false);
    expect(isReportContent('invalid')).toBe(false);
  });

  it('returns false when detail.items is missing or not an array', () => {
    const invalid = {
      ...validContent,
      detail: { items: 'not an array' },
    };
    expect(isReportContent(invalid)).toBe(false);
  });

  it('returns false when recommendation.items is missing or not an array', () => {
    const invalid = {
      ...validContent,
      recommendation: {},
    };
    expect(isReportContent(invalid)).toBe(false);
  });
});
