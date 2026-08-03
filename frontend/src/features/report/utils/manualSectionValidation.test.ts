import { describe, expect, it } from 'vitest';
import type { ManualSection, ReportContent } from '../types';
import { getEmptyManualSectionLabels } from './manualSectionValidation';

function contentWith(manualSections: ManualSection[]): ReportContent {
  return {
    overview: { purpose: '', facility_summary: '', scope: '' },
    summary: { overall_opinion: '', total_count: 0, count_by_grade: {}, key_findings: [] },
    detail: { items: [] },
    recommendation: { items: [], monitoring_points: [] },
    manualSections,
  };
}

describe('getEmptyManualSectionLabels — location-drawing-photos', () => {
  it('이미지가 없으면 빈 섹션으로 판단한다', () => {
    const content = contentWith([
      { id: 'a', type: 'location-drawing-photos', title: '위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도', data: { images: [] } },
    ]);
    expect(getEmptyManualSectionLabels(content)).toEqual(['위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도']);
  });

  it('이미지가 1장 이상이면 빈 섹션이 아니다', () => {
    const content = contentWith([
      {
        id: 'a',
        type: 'location-drawing-photos',
        title: '위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도',
        data: { images: [{ dataUrl: 'data:image/jpeg;base64,AAA', caption: '' }] },
      },
    ]);
    expect(getEmptyManualSectionLabels(content)).toEqual([]);
  });
});
