import { describe, expect, it } from 'vitest';
import type { ManualSection, ReportContent } from '../types';
import { getEmptyManualSectionLabels, getMissingFinalReportRequiredLabels } from './manualSectionValidation';

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

describe('getEmptyManualSectionLabels — participants', () => {
  it('참여 기술진 행의 일부 필드만 입력되어 있으면 빈 섹션으로 판단한다', () => {
    const content = contentWith([
      {
        id: 'p1',
        type: 'participants',
        title: '참여 기술진 명단',
        data: {
          entries: [
            { role: '책임기술자', name: '', qualification: '', period: '' },
          ],
        },
      },
    ]);
    expect(getEmptyManualSectionLabels(content)).toEqual(['참여 기술진 명단']);
  });

  it('참여 기술진 행의 역할·이름·자격·참여기간이 모두 채워지면 빈 섹션이 아니다', () => {
    const content = contentWith([
      {
        id: 'p1',
        type: 'participants',
        title: '참여 기술진 명단',
        data: {
          entries: [
            { role: '책임기술자', name: '홍길동', qualification: '특급기술자', period: '2026.01~2026.06' },
            { role: '', name: '', qualification: '', period: '' }, // 완전히 빈 행은 무시
          ],
        },
      },
    ]);
    expect(getEmptyManualSectionLabels(content)).toEqual([]);
  });
});

describe('getMissingFinalReportRequiredLabels with reportOptions', () => {
  it('details 섹션 제외 시 하자 상세가 없어도 검증 필수 항목으로 요구하지 않는다', () => {
    const content: ReportContent = {
      overview: { purpose: '목적', facility_summary: '개요', scope: '범위' },
      summary: { overall_opinion: '의견', total_count: 0, count_by_grade: {}, key_findings: [] },
      detail: { items: [] },
      recommendation: { items: [{ method: '방법', target: '대상', priority: '상', legal_basis: '', legal_basis_verified: false }], monitoring_points: [] },
      reportOptions: { sections: ['overview', 'summary', 'recommendation'] },
    };

    expect(getMissingFinalReportRequiredLabels(content)).toEqual([]);
  });

  it('details 섹션 포함 시 하자 상세가 없으면 검증 필수 항목으로 요구한다', () => {
    const content: ReportContent = {
      overview: { purpose: '목적', facility_summary: '개요', scope: '범위' },
      summary: { overall_opinion: '의견', total_count: 0, count_by_grade: {}, key_findings: [] },
      detail: { items: [] },
      recommendation: { items: [{ method: '방법', target: '대상', priority: '상', legal_basis: '', legal_basis_verified: false }], monitoring_points: [] },
      reportOptions: { sections: ['overview', 'summary', 'details', 'recommendation'] },
    };

    expect(getMissingFinalReportRequiredLabels(content)).toContain('진단 외관조사결과 기본사항');
  });
});
