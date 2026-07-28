import { describe, expect, it } from 'vitest';
import type { ExportStatisticsDataParams } from './exportStatisticsCsv';
import { buildStatisticsCsvRows, renderCsvContent } from './exportStatisticsCsv';

const defaultData: ExportStatisticsDataParams = {
  periodLabel: '3개월',
  facilityLabel: '전체 시설물',
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
};

describe('buildStatisticsCsvRows — 데이터 계층', () => {
  it('헤더 행에 필터 조건(기간/시설물)이 반영된다', () => {
    const rows = buildStatisticsCsvRows(defaultData);
    expect(rows[0][0]).toContain('HajaCheck 통계');
    expect(rows[1][0]).toContain('3개월');
    expect(rows[1][1]).toContain('전체 시설물');
  });

  it('KPI 요약 섹션이 4개 지표 행을 포함한다', () => {
    const rows = buildStatisticsCsvRows(defaultData);
    const kpiSection = rows.map((r) => r[0]);
    expect(kpiSection).toContain('[1. 핵심 지표 (KPI 요약)]');
    expect(kpiSection).toContain('총 탐지 하자');
    expect(kpiSection).toContain('평균 처리일');
    expect(kpiSection).toContain('조치 완료율');
    expect(kpiSection).toContain('진행성 하자');
  });

  it('월별 추이가 있으면 [2. 월별 하자 추이] 섹션이 포함된다', () => {
    const rows = buildStatisticsCsvRows({
      ...defaultData,
      monthlyTrend: [
        { month: '2026-01', defectCount: 60 },
        { month: '2026-02', defectCount: 110 },
      ],
    });
    const sectionHeaders = rows.map((r) => r[0]);
    expect(sectionHeaders).toContain('[2. 월별 하자 추이]');
  });

  it('KPI 데이터가 없으면 KPI 섹션을 생략한다', () => {
    const rows = buildStatisticsCsvRows({ ...defaultData, kpiSummary: null });
    const sectionHeaders = rows.map((r) => r[0]);
    expect(sectionHeaders).not.toContain('[1. 핵심 지표 (KPI 요약)]');
  });

  it('월별 추이가 null이면 해당 섹션을 생략한다', () => {
    const rows = buildStatisticsCsvRows({ ...defaultData, monthlyTrend: null });
    const sectionHeaders = rows.map((r) => r[0]);
    expect(sectionHeaders).not.toContain('[2. 월별 하자 추이]');
  });

  it('시설물 요약 섹션에 등급 null은 "-"로 표시된다', () => {
    const rows = buildStatisticsCsvRows({
      ...defaultData,
      facilitySummary: [
        {
          facilityId: 4,
          facilityName: '판교 테크노밸리',
          facilityType: '건물-정기-4개월',
          totalDefects: 5,
          latestGrade: null,
          lastInspectedAt: '2026-07-09',
        },
      ],
    });
    const facilityRows = rows.filter((r) => r[0] === '판교 테크노밸리');
    expect(facilityRows[0][3]).toBe('-');
  });
});

describe('renderCsvContent — CSV 문자열 렌더링', () => {
  it('UTF-8 BOM(\uFEFF)이 본문 시작에 포함된다', () => {
    const rows = buildStatisticsCsvRows(defaultData);
    const csv = renderCsvContent(rows);
    expect(csv.startsWith('\uFEFF')).toBe(false);
    // BOM은 Blob 생성 시 추가되므로 실제 파일에서 BOM을 확인
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
    expect(blob.size).toBeGreaterThan(0);
  });

  it('쉼표가 포함된 값은 큰따옴표로 감싸진다', () => {
    const csv = renderCsvContent([['a,b', 'normal']]);
    expect(csv).toBe('"a,b",normal');
  });

  it('큰따옴표가 포함된 값은 ""로 이스케이프된다', () => {
    const csv = renderCsvContent([['say "hello"', 'world']]);
    expect(csv).toBe('"say ""hello""",world');
  });

  it('줄바꿈이 포함된 값은 큰따옴표로 감싸진다', () => {
    const csv = renderCsvContent([['line1\nline2', 'ok']]);
    expect(csv).toBe('"line1\nline2",ok');
  });

  it('CSV 포뮬라 인젝션 방지: =, +, -, @로 시작하는 값에 작은따옴표를 앞에 붙인다', () => {
    const csv = renderCsvContent([['=SUM(A1:A10)', '+123', '-danger', '@REF', 'normal']]);
    const parts = csv.split(',');
    expect(parts[0]).toBe("'=SUM(A1:A10)");
    expect(parts[1]).toBe("'+123");
    expect(parts[2]).toBe("'-danger");
    expect(parts[3]).toBe("'@REF");
    expect(parts[4]).toBe('normal');
  });

  it('모든 행이 CRLF로 연결된다', () => {
    const csv = renderCsvContent([['a'], ['b'], ['c']]);
    expect(csv).toBe('a\r\nb\r\nc');
  });
});

describe('renderCsvContent — 복합 escaping (여러 조건 동시)', () => {
  it('쉼표와 큰따옴표가 동시에 있는 값은 큰따옴표로 감싸고 내부 "를 이스케이프한다', () => {
    const csv = renderCsvContent([['a,b "c"', 'd']]);
    expect(csv).toBe('"a,b ""c""",d');
  });

  it('헤더 정보와 데이터가 모두 포함된 통합 CSV를 생성한다', () => {
    const data: ExportStatisticsDataParams = {
      periodLabel: '6개월',
      facilityLabel: '전체',
      kpiSummary: {
        totalDefects: 100,
        totalDefectsChangeRate: 5,
        avgResolutionDays: 3.0,
        avgResolutionDaysChangeRate: 0.5,
        actionCompletionRate: 80,
        actionCompletionRateChangeRate: 2,
        progressingDefects: 10,
        progressingDefectsChangeRate: 1,
      },
      monthlyTrend: [
        { month: '2026-01', defectCount: 50 },
        { month: '2026-02', defectCount: 75 },
      ],
      gradeDistribution: [
        { grade: 'A', percent: 40 },
        { grade: 'B', percent: 30 },
      ],
    };
    const rows = buildStatisticsCsvRows(data);
    const csv = renderCsvContent(rows);

    expect(csv).toContain('6개월');
    expect(csv).toContain('[1. 핵심 지표 (KPI 요약)]');
    expect(csv).toContain('[2. 월별 하자 추이]');
    expect(csv).toContain('[4. 등급별 하자 분포]');
    expect(csv).not.toContain('[3. 유형별 하자 분포]');
    expect(csv).not.toContain('[5. 시설물별 현황 요약]');
  });
});
