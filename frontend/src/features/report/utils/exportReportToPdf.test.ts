// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReportContent } from '../types';
import { mockReportDetailResponse } from '../mocks/reportDetail.mock';
import { buildReportPdfFileName, exportReportToPdf } from './exportReportToPdf';

const mockOutput = vi.fn().mockReturnValue(new Blob(['fake-pdf-bytes']));
const mockAddFileToVFS = vi.fn();
const mockAddFont = vi.fn();
const mockSetFont = vi.fn();
const mockSetFontSize = vi.fn();
const mockText = vi.fn();
const mockAddPage = vi.fn();
const mockSplitTextToSize = vi.fn((text: string) => [text]);
const mockAddImage = vi.fn();
const mockAutoTable = vi.fn((doc: MockJsPDF, _options: unknown) => {
  void _options;
  doc.lastAutoTable = { finalY: 120 };
});

class MockJsPDF {
  addFileToVFS = mockAddFileToVFS;
  addFont = mockAddFont;
  setFont = mockSetFont;
  setFontSize = mockSetFontSize;
  text = mockText;
  addPage = mockAddPage;
  splitTextToSize = mockSplitTextToSize;
  addImage = mockAddImage;
  lastAutoTable = { finalY: 0 };
  setLineHeightFactor = vi.fn();
  setDrawColor = vi.fn();
  setLineWidth = vi.fn();
  setTextColor = vi.fn();
  rect = vi.fn();
  line = vi.fn();
  getNumberOfPages = vi.fn(() => 5);
  setPage = vi.fn();
  output = mockOutput;
}

vi.mock('jspdf', () => ({
  default: MockJsPDF,
}));

vi.mock('jspdf-autotable', () => ({
  default: mockAutoTable,
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
    mockAddImage.mockClear();
    mockAutoTable.mockClear();

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
    expect(mockAddFont).toHaveBeenCalledWith('Pretendard-Bold.ttf', 'Pretendard', 'bold');
    expect(mockAutoTable).toHaveBeenCalled();
    expect(mockText).toHaveBeenCalled();
    expect(mockOutput).toHaveBeenCalledWith('blob');
    expect(blob).toBeInstanceOf(Blob);
  });

  it('실백엔드 상세 응답 fixture의 content로 PDF Blob을 생성한다', async () => {
    const blob = await exportReportToPdf(mockReportDetailResponse.content as ReportContent);

    expect(mockText).toHaveBeenCalledWith('정밀안전점검 보고서', 105, 96, { align: 'center' });
    expect(mockText).toHaveBeenCalledWith('시설물 안전점검 결과', 105, 108, { align: 'center' });
    expect(mockAutoTable).toHaveBeenCalledWith(expect.any(MockJsPDF), expect.objectContaining({
      head: [['번호', '결함 종류', '발생 위치', '등급', '조사 결과', '추정 원인']],
    }));
    expect(mockOutput).toHaveBeenCalledWith('blob');
    expect(blob).toBeInstanceOf(Blob);
  });

  it('점검 축소본을 사진대지에 넣고 상세 표의 행 분할을 피한다', async () => {
    vi.mocked(fetch).mockImplementation((input) => {
      if (String(input) === '/api/media/1/thumbnail') {
        return Promise.resolve({
          ok: true,
          blob: () => Promise.resolve(new Blob(['jpeg-bytes'], { type: 'image/jpeg' })),
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob(['font-bytes'])),
      } as Response);
    });

    await exportReportToPdf(makeContent(), {
      defectImages: [{ defectType: '균열', imageUrl: '/api/media/1/thumbnail' }],
    });

    expect(mockAddImage).toHaveBeenCalledWith(expect.any(String), 'JPEG', 18, expect.any(Number), 174, 96);
    expect(mockAutoTable.mock.calls.some(([, options]) =>
      (options as { rowPageBreak?: string; showHead?: string }).rowPageBreak === 'avoid'
      && (options as { rowPageBreak?: string; showHead?: string }).showHead === 'everyPage',
    )).toBe(true);
  });

  it('점검 축소본이 없어도 사진대지 섹션을 해당 없음으로 출력해 섹션 번호를 유지한다', async () => {
    await exportReportToPdf(makeContent(), { defectImages: [] });

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).toContain('3. 대상시설물 부위별 사진');
    expect(mockAutoTable).toHaveBeenCalledWith(expect.any(MockJsPDF), expect.objectContaining({
      head: [['대상시설물 부위별 사진']],
      body: [['점검 촬영 축소본이 없습니다.']],
    }));
    expect(renderedText).toContain('4. 보수·보강방안');
    expect(renderedText).toContain('5. 종합결론 및 건의');
  });

  it('공식 용어에 가까운 제목과 섹션명을 쓰되 지원되지 않는 서명·참여자 필드는 만들지 않는다', async () => {
    await exportReportToPdf(makeContent(), { issuedAt: new Date('2026-07-26T00:00:00') });

    const renderedText = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedText).toContain('정밀안전점검 보고서');
    expect(renderedText).toContain('1. 정밀안전점검 결과표');
    expect(renderedText).toContain('2. 정밀안전점검 실시결과 요약문');
    expect(renderedText).toContain('3. 대상시설물 부위별 사진');
    expect(renderedText).toContain('4. 보수·보강방안');
    expect(renderedText).toContain('5. 종합결론 및 건의');
    expect(renderedText).not.toContain('책임기술자 종합의견');
    expect(renderedText).not.toContain('작성자');
    expect(renderedText).not.toContain('(서명)');
    expect(renderedText).not.toContain('참여기술자');
    expect(renderedText).not.toContain('입회자');
    expect(renderedText.some((text) =>
      typeof text === 'string' && (
        text.includes('HajaCheck defect inspection report')
        || text.includes('non-statutory')
        || text.includes('공식 제출 서류를 대체하지 않습니다')
        || text.includes('참고 보고서')
      ),
    )).toBe(false);
  });

  it('context에 없는 작성 기준일은 현재 날짜로 채우지 않고 빈 값으로 둔다', async () => {
    await exportReportToPdf(makeContent());

    expect(mockAutoTable).toHaveBeenCalledWith(expect.any(MockJsPDF), expect.objectContaining({
      body: expect.arrayContaining([
        ['작성 기준일', '-'],
      ]),
    }));
  });

  it('미검증 법령 근거는 공식 근거처럼 표시하지 않고 미검증 표식을 붙인다', async () => {
    await exportReportToPdf(makeContent({
      recommendation: {
        items: [{ target: '1층 벽체', method: '보수', priority: '중', legal_basis: '관련 근거 없음', legal_basis_verified: false }],
        monitoring_points: [],
      },
    }));

    expect(mockAutoTable).toHaveBeenCalledWith(expect.any(MockJsPDF), expect.objectContaining({
      head: [['번호', '대상 부위', '권고 조치', '우선순위', '근거']],
      body: [['1', '1층 벽체', '보수', '중', '관련 근거 없음 (미검증)']],
    }));
  });

  it('buildReportPdfFileName은 inspectionId와 오늘 날짜로 파일명을 만든다', () => {
    expect(buildReportPdfFileName(42)).toMatch(/^점검보고서_42_\d{8}\.pdf$/);
  });
});
