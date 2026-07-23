// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReportContent } from '../types';
import { buildReportPdfFileName, exportReportToPdf } from './exportReportToPdf';

const mockOutput = vi.fn().mockReturnValue(new Blob(['fake-pdf-bytes']));
const mockAddFileToVFS = vi.fn();
const mockAddFont = vi.fn();
const mockSetFont = vi.fn();
const mockSetFontSize = vi.fn();
const mockText = vi.fn();
const mockAddPage = vi.fn();
const mockSplitTextToSize = vi.fn((text: string) => [text]);

class MockJsPDF {
  addFileToVFS = mockAddFileToVFS;
  addFont = mockAddFont;
  setFont = mockSetFont;
  setFontSize = mockSetFontSize;
  text = mockText;
  addPage = mockAddPage;
  splitTextToSize = mockSplitTextToSize;
  output = mockOutput;
}

vi.mock('jspdf', () => ({
  default: MockJsPDF,
}));

vi.mock('pretendard/dist/public/static/alternative/Pretendard-Regular.ttf?url', () => ({
  default: 'https://example.test/Pretendard-Regular.ttf',
}));

function makeContent(overrides: Partial<ReportContent> = {}): ReportContent {
  return {
    overview: { purpose: '정기 점검', facility_summary: '테스트 시설물', scope: '전체' },
    summary: {
      overall_opinion: '양호',
      total_count: 1,
      count_by_grade: { A: 0, B: 0, C: 1, D: 0, E: 0 },
      key_findings: ['균열 발견'],
    },
    detail: {
      items: [
        { defect_type: '균열', location: '1층 벽체', severity_grade: 'C', description: '설명', cause: '원인' },
      ],
    },
    recommendation: {
      items: [{ target: '1층 벽체', method: '보수', priority: '중', legal_basis: '관련 근거 없음', legal_basis_verified: false }],
      monitoring_points: ['정기 재점검'],
    },
    ...overrides,
  };
}

describe('exportReportToPdf', () => {
  beforeEach(() => {
    mockOutput.mockClear();
    mockAddFileToVFS.mockClear();
    mockAddFont.mockClear();
    mockSetFont.mockClear();
    mockSetFontSize.mockClear();
    mockText.mockClear();
    mockAddPage.mockClear();
    mockSplitTextToSize.mockClear();

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        blob: () => Promise.resolve(new Blob(['fake-font-bytes'])),
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('폰트를 임베딩하고 content 섹션들을 렌더링한 뒤 Blob을 반환한다', async () => {
    const blob = await exportReportToPdf(makeContent());

    expect(mockAddFont).toHaveBeenCalledWith('Pretendard-Regular.ttf', 'Pretendard', 'normal');
    expect(mockText).toHaveBeenCalled();
    expect(mockOutput).toHaveBeenCalledWith('blob');
    expect(blob).toBeInstanceOf(Blob);
  });

  it('buildReportPdfFileName은 inspectionId와 오늘 날짜로 파일명을 만든다', () => {
    expect(buildReportPdfFileName(42)).toMatch(/^점검보고서_42_\d{8}\.pdf$/);
  });
});
