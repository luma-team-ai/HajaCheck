// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildStatisticsPdfDocument, type StatisticsPdfDataParams } from './exportStatisticsAsPdf';

const mockSave = vi.fn();
const mockText = vi.fn();
const mockSetFont = vi.fn();
const mockSetFontSize = vi.fn();
const mockAddPage = vi.fn();
const mockAutoTable = vi.fn((doc: MockJsPDF, _options: unknown) => {
  void _options;
  doc.lastAutoTable = { finalY: 120 };
});

class MockJsPDF {
  setFont = mockSetFont;
  setFontSize = mockSetFontSize;
  text = mockText;
  setTextColor = vi.fn();
  setDrawColor = vi.fn();
  setLineHeightFactor = vi.fn();
  addPage = mockAddPage;
  save = mockSave;
  addFileToVFS = vi.fn();
  addFont = vi.fn();
  lastAutoTable = { finalY: 0 };
}

vi.mock('jspdf', () => ({
  default: MockJsPDF,
}));

vi.mock('jspdf-autotable', () => ({
  default: mockAutoTable,
}));

const mockRegisterNotoSansKrFont = vi.fn().mockResolvedValue(undefined);
vi.mock('../../../shared/utils/pdfFont', () => ({
  PDF_FONT_NAME: 'NotoSansKR',
  registerNotoSansKrFont: (...args: unknown[]) => mockRegisterNotoSansKrFont(...args),
}));

/** autoTable 호출 중 조건에 맞는 첫 옵션을 찾는다(exportReportToPdf.test.ts와 동일 관용구). */
function findTableOptions(
  predicate: (options: Record<string, unknown>) => boolean,
): Record<string, unknown> | undefined {
  return mockAutoTable.mock.calls
    .map(([, options]) => options as Record<string, unknown>)
    .find(predicate);
}

function findSectionTable(head: string[]): Record<string, unknown> | undefined {
  return findTableOptions((options) => JSON.stringify(options.head) === JSON.stringify([head]));
}

const FULL_PARAMS: StatisticsPdfDataParams = {
  periodLabel: '최근 6개월',
  facilityLabel: '전체 시설물',
  issuedAt: new Date('2026-08-19T10:00:00'),
  kpiSummary: {
    totalDefects: 1842,
    totalDefectsChangeRate: 12,
    avgResolutionDays: 4.2,
    avgResolutionDaysChangeRate: 0.8,
    actionCompletionRate: 76,
    actionCompletionRateChangeRate: 5,
    progressingDefects: 38,
    progressingDefectsChangeRate: 2,
  },
  monthlyTrend: [
    { month: '2026-07', defectCount: 195 },
    { month: '2026-08', defectCount: 342 },
  ],
  defectTypeDistribution: [
    { type: '균열', count: 128 },
    { type: '박리·박락', count: 40 },
  ],
  gradeDistribution: [
    { grade: 'A', percent: 40 },
    { grade: 'E', percent: 5 },
  ],
  facilityTypeHeatmap: [
    { facilityTypeCategory: '건물', month: '2026-08', defectCount: 12 },
    { facilityTypeCategory: '교량', month: '2026-08', defectCount: 3 },
  ],
  heatmapMonths: ['2026-08'],
  facilitySummary: [
    {
      facilityId: 1,
      facilityName: '여의도 파크센터',
      facilityType: '건물-정기-4개월',
      totalDefects: 20,
      latestGrade: 'B',
      lastInspectedAt: '2026-08-01',
    },
  ],
};

describe('exportStatisticsAsPdf', () => {
  beforeEach(() => {
    mockSave.mockClear();
    mockText.mockClear();
    mockSetFont.mockClear();
    mockSetFontSize.mockClear();
    mockAddPage.mockClear();
    mockAutoTable.mockClear();
    mockRegisterNotoSansKrFont.mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('html2canvas류를 쓰지 않고 폰트를 등록한 뒤 표 문서를 저장한다', async () => {
    const { exportStatisticsAsPdf } = await import('./exportStatisticsAsPdf');

    await exportStatisticsAsPdf(FULL_PARAMS);

    expect(mockRegisterNotoSansKrFont).toHaveBeenCalledTimes(1);
    expect(mockText.mock.calls.some(([text]) => text === '통계 리포트')).toBe(true);
    expect(mockSave).toHaveBeenCalledTimes(1);
    expect(mockSave.mock.calls[0][0]).toMatch(/^통계리포트_\d{8}\.pdf$/);
  });

  it('데이터가 모두 있으면 조회 조건 표 + 6개 섹션 표를 전부 그린다', async () => {
    const { exportStatisticsAsPdf } = await import('./exportStatisticsAsPdf');

    await exportStatisticsAsPdf(FULL_PARAMS);

    const conditionTable = findTableOptions((options) => {
      const body = options.body as unknown[][];
      return Array.isArray(body) && body.some((row) => row[0] === '조회 기간');
    });
    expect(conditionTable).toBeDefined();
    expect(findSectionTable(['지표', '현재 값', '변화율'])).toBeDefined();
    expect(findSectionTable(['월', '하자 건수'])).toBeDefined();
    expect(findSectionTable(['하자 유형', '탐지 건수'])).toBeDefined();
    expect(findSectionTable(['등급', '비율'])).toBeDefined();
    expect(findSectionTable(['시설물군', '8월'])).toBeDefined();
    expect(findSectionTable(['시설물명', '시설물 유형', '총 하자수', '최근 등급', '최근 점검일'])).toBeDefined();

    const renderedTitles = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedTitles).toContain('1. 핵심 지표');
    expect(renderedTitles).toContain('2. 월별 하자 추이');
    expect(renderedTitles).toContain('6. 시설물별 현황 요약');
  });

  it('데이터가 없는 섹션은 표를 만들지 않고, 남은 섹션 번호를 당겨서 매긴다', async () => {
    const { exportStatisticsAsPdf } = await import('./exportStatisticsAsPdf');

    await exportStatisticsAsPdf({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      kpiSummary: FULL_PARAMS.kpiSummary,
      monthlyTrend: [],
      defectTypeDistribution: null,
      gradeDistribution: FULL_PARAMS.gradeDistribution,
      facilityTypeHeatmap: undefined,
      facilitySummary: undefined,
    });

    expect(findSectionTable(['월', '하자 건수'])).toBeUndefined();
    expect(findSectionTable(['하자 유형', '탐지 건수'])).toBeUndefined();
    expect(findSectionTable(['시설물군', '8월'])).toBeUndefined();
    expect(findSectionTable(['시설물명', '시설물 유형', '총 하자수', '최근 등급', '최근 점검일'])).toBeUndefined();

    const renderedTitles = mockText.mock.calls.map(([text]) => text).flat();
    expect(renderedTitles).toContain('1. 핵심 지표');
    // 월별 추이(빈 배열)·유형별 분포(null)가 빠지므로 등급별 분포는 2번을 그대로 이어받는다.
    expect(renderedTitles).toContain('2. 등급별 하자 분포');
    expect(renderedTitles).not.toContain('4. 등급별 하자 분포');
  });
});

// buildStatisticsPdfDocument — jsPDF/DOM/캔버스를 전혀 참조하지 않는 순수 함수라 mock 없이
// 직접 단위 테스트한다. 삭제한 exportStatisticsCsv.test.ts의 케이스 구성(섹션별 행 내용, 데이터
// 없을 때 섹션 생략)을 그대로 참고했다.
describe('buildStatisticsPdfDocument — 표 행 조립(순수 함수)', () => {
  it('조회 조건 표에 기간·시설물 라벨·출력 일시가 들어간다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '여의도 파크센터',
      issuedAt: new Date('2026-08-19T10:00:00'),
    });

    expect(doc.conditionRows).toEqual([
      ['조회 기간', '최근 3개월'],
      ['시설물 범위', '여의도 파크센터'],
      ['출력 일시', new Date('2026-08-19T10:00:00').toLocaleString('ko-KR')],
    ]);
  });

  it('핵심 지표 4개 행을 지표|현재 값|변화율 형식으로 만든다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      kpiSummary: FULL_PARAMS.kpiSummary,
    });

    const section = doc.sections.find((s) => s.title === '1. 핵심 지표');
    expect(section?.head).toEqual(['지표', '현재 값', '변화율']);
    expect(section?.rows).toEqual([
      ['총 탐지 하자', '1,842건', '12%'],
      ['평균 처리일', '4.2일', '0.8일'],
      ['조치 완료율', '76%', '5%'],
      ['진행성 하자', '38건', '2건'],
    ]);
  });

  it('kpiSummary가 없으면 핵심 지표 섹션 자체를 만들지 않는다', () => {
    const doc = buildStatisticsPdfDocument({ periodLabel: '최근 3개월', facilityLabel: '전체 시설물' });
    expect(doc.sections.find((s) => s.title.includes('핵심 지표'))).toBeUndefined();
    expect(doc.sections).toHaveLength(0);
  });

  it('월별 하자 추이는 월 라벨(M월)과 건수로 행을 만든다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      monthlyTrend: [
        { month: '2026-07', defectCount: 195 },
        { month: '2026-08', defectCount: 342 },
      ],
    });

    const section = doc.sections[0];
    expect(section.title).toBe('1. 월별 하자 추이');
    expect(section.rows).toEqual([
      ['7월', '195건'],
      ['8월', '342건'],
    ]);
  });

  it('빈 배열이면(월별 하자 추이 등) 섹션을 생략한다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      monthlyTrend: [],
    });
    expect(doc.sections).toHaveLength(0);
  });

  it('등급별 분포는 등급 뒤에 "등급"을 붙이고 비율을 %로 붙인다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      gradeDistribution: [
        { grade: 'A', percent: 40 },
        { grade: 'E', percent: 5 },
      ],
    });

    expect(doc.sections[0].rows).toEqual([
      ['A등급', '40%'],
      ['E등급', '5%'],
    ]);
  });

  it('시설물군별 히트맵은 화면과 동일하게 건물/교량/도로/기타 4행을 항상 채우고, 데이터 없는 칸은 0건으로 표시한다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      facilityTypeHeatmap: [{ facilityTypeCategory: '건물', month: '2026-08', defectCount: 12 }],
      heatmapMonths: ['2026-08'],
    });

    const section = doc.sections[0];
    expect(section.head).toEqual(['시설물군', '8월']);
    expect(section.rows).toEqual([
      ['건물', '12건'],
      ['교량', '0건'],
      ['도로', '0건'],
      ['기타', '0건'],
    ]);
  });

  it('facilityTypeHeatmap이 비어 있으면 히트맵 섹션 자체를 생략한다(고정 행이라도 만들지 않음)', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      facilityTypeHeatmap: [],
    });
    expect(doc.sections).toHaveLength(0);
  });

  it('heatmapMonths를 생략하면 셀 데이터에 등장하는 월만 오름차순으로 쓴다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      facilityTypeHeatmap: [
        { facilityTypeCategory: '건물', month: '2026-06', defectCount: 1 },
        { facilityTypeCategory: '건물', month: '2026-08', defectCount: 2 },
      ],
    });
    expect(doc.sections[0].head).toEqual(['시설물군', '6월', '8월']);
  });

  it('시설물별 현황 요약은 등급 표기·최근 점검일 미기재를 "-"로 처리한다', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      facilitySummary: [
        {
          facilityId: 1,
          facilityName: '여의도 파크센터',
          facilityType: '건물-정기-4개월',
          totalDefects: 20,
          latestGrade: 'B',
          lastInspectedAt: '2026-08-01',
        },
        {
          facilityId: 2,
          facilityName: '점검 이력 없는 시설물',
          facilityType: '건물-정기-4개월',
          totalDefects: 0,
          latestGrade: null,
          lastInspectedAt: null,
        },
      ],
    });

    expect(doc.sections[0].rows).toEqual([
      ['여의도 파크센터', '건물-정기-4개월', '20건', 'B등급', '2026-08-01'],
      ['점검 이력 없는 시설물', '건물-정기-4개월', '0건', '-', '-'],
    ]);
  });

  it('여러 섹션이 함께 있으면 절 번호를 1부터 순서대로 매긴다(데이터 없는 섹션은 건너뛰고 당김)', () => {
    const doc = buildStatisticsPdfDocument({
      periodLabel: '최근 3개월',
      facilityLabel: '전체 시설물',
      kpiSummary: FULL_PARAMS.kpiSummary,
      monthlyTrend: null,
      defectTypeDistribution: FULL_PARAMS.defectTypeDistribution,
    });

    expect(doc.sections.map((s) => s.title)).toEqual(['1. 핵심 지표', '2. AI 탐지 유형별 분포']);
  });
});
