import type {
  DefectTypeDistributionItem,
  FacilitySummaryItem,
  GradeDistributionItem,
  MonthlyDefectTrendItem,
  StatisticsKpiSummary,
} from '../types';

export interface ExportStatisticsDataParams {
  periodLabel: string;
  facilityLabel: string;
  kpiSummary?: StatisticsKpiSummary | null;
  monthlyTrend?: MonthlyDefectTrendItem[] | null;
  defectTypeDistribution?: DefectTypeDistributionItem[] | null;
  gradeDistribution?: GradeDistributionItem[] | null;
  facilitySummary?: FacilitySummaryItem[] | null;
}

export function exportStatisticsToCsv(data: ExportStatisticsDataParams): void {
  const rows: string[][] = [];

  rows.push(['HajaCheck 통계 대시보드 리포트']);
  rows.push([
    `조회 기간: ${data.periodLabel}`,
    `시설물 범위: ${data.facilityLabel}`,
    `출력 일시: ${new Date().toLocaleString('ko-KR')}`,
  ]);
  rows.push([]);

  if (data.kpiSummary) {
    rows.push(['[1. 핵심 지표 (KPI 요약)]']);
    rows.push(['지표명', '현재 값', '변화율']);
    rows.push([
      '총 탐지 하자',
      `${data.kpiSummary.totalDefects.toLocaleString()}건`,
      `${data.kpiSummary.totalDefectsChangeRate}%`,
    ]);
    rows.push([
      '평균 처리일',
      `${data.kpiSummary.avgResolutionDays}일`,
      `${data.kpiSummary.avgResolutionDaysChangeRate}일`,
    ]);
    rows.push([
      '조치 완료율',
      `${data.kpiSummary.actionCompletionRate}%`,
      `${data.kpiSummary.actionCompletionRateChangeRate}%`,
    ]);
    rows.push([
      '진행성 하자',
      `${data.kpiSummary.progressingDefects}건`,
      `${data.kpiSummary.progressingDefectsChangeRate}건`,
    ]);
    rows.push([]);
  }

  if (data.monthlyTrend && data.monthlyTrend.length > 0) {
    rows.push(['[2. 월별 하자 추이]']);
    rows.push(['월', '하자 건수']);
    data.monthlyTrend.forEach((item) => {
      rows.push([item.month, `${item.defectCount}건`]);
    });
    rows.push([]);
  }

  if (data.defectTypeDistribution && data.defectTypeDistribution.length > 0) {
    rows.push(['[3. 유형별 하자 분포]']);
    rows.push(['하자 유형', '탐지 건수']);
    data.defectTypeDistribution.forEach((item) => {
      rows.push([item.type, `${item.count}건`]);
    });
    rows.push([]);
  }

  if (data.gradeDistribution && data.gradeDistribution.length > 0) {
    rows.push(['[4. 등급별 하자 분포]']);
    rows.push(['등급', '비율']);
    data.gradeDistribution.forEach((item) => {
      rows.push([`${item.grade}등급`, `${item.percent}%`]);
    });
    rows.push([]);
  }

  if (data.facilitySummary && data.facilitySummary.length > 0) {
    rows.push(['[5. 시설물별 현황 요약]']);
    rows.push(['시설물명', '시설물 유형', '총 하자수', '최근 등급', '최근 점검일']);
    data.facilitySummary.forEach((item) => {
      rows.push([
        item.facilityName,
        item.facilityType,
        `${item.totalDefects}건`,
        item.latestGrade ? `${item.latestGrade}등급` : '-',
        item.lastInspectedAt ?? '-',
      ]);
    });
    rows.push([]);
  }

  const csvContent = rows
    .map((row) =>
      row
        .map((cell) => {
          const raw = String(cell ?? '');
          // 시설물명 등 사용자 입력값이 CSV로 그대로 나가므로, Excel 등이 셀을 수식으로 해석하지
          // 않도록 =/+/-/@로 시작하는 값은 앞에 작은따옴표를 붙여 무력화한다(CSV formula injection).
          const str = /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw;
          if (str.includes(',') || str.includes('"') || str.includes('\n')) {
            return `"${str.replace(/"/g, '""')}"`;
          }
          return str;
        })
        .join(','),
    )
    .join('\r\n');

  const blob = new Blob(['\uFEFF' + csvContent], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  const dateStr = new Date().toISOString().split('T')[0];
  link.href = url;
  link.setAttribute('download', `hajacheck_statistics_${dateStr}.csv`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
