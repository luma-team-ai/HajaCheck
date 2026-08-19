import { PDF_FONT_NAME, registerNotoSansKrFont } from '../../../shared/utils/pdfFont';
import { formatMonthLabel } from './formatMonthLabel';
import type {
  DefectTypeDistributionItem,
  FacilitySummaryItem,
  FacilityTypeCategory,
  FacilityTypeMonthlyHeatmapCell,
  GradeDistributionItem,
  MonthlyDefectTrendItem,
  StatisticsKpiSummary,
} from '../types';

// #1692 — 통계 화면 "내보내기"를 화면 캡처 이미지가 아니라, 이 레포의 다른 정식 산출물(관공서
// 정밀안전진단 표준서식, features/report/utils/exportReportToPdf.ts)과 같은 계열의 텍스트 기반
// 문서로 만든다(2026-08-19 방향 전환 — prod 실제 산출물 `점검보고서_..._초안.pdf`를 사용자가
// 제시해 확인: 텍스트가 살아있는 표 문서였고, 화면 캡처 이미지 1장(텍스트 0자)은 이 프로젝트
// 문서 기준과 어긋났다). 그래서 html2canvas류는 전혀 쓰지 않고, jsPDF+jspdf-autotable로 표를
// 직접 그린다. 색은 회색(#CCCCCC) 헤더/라벨 배경뿐이고 화면의 컬러 차트·히트맵 음영은 숫자
// 표로만 옮긴다(원본 서식이 흑백 괘선+표 기반이라 컬러가 섞이면 톤이 깨진다).
//
// 아래 규격 상수(MARGIN_X·GRAY_HEADER_STYLES·LINE_INNER/OUTER·FONT_SIZE)는 exportReportToPdf.ts가
// 이미 실물 서식을 계측해 정한 값을 그대로 따른다(같은 문서 계열로 보여야 하므로). exportReportToPdf.ts
// 자체는 현재 prod 산출물이라 회귀 위험 때문에 수정하지 않고, 폰트 로딩만 shared/utils/pdfFont.ts로
// 빼서 이 파일이 함께 쓴다 — 표 조립 상수·헬퍼까지 공용 모듈로 승격하는 건 회귀 없이 검증할 시간이
// 필요해 후속 이슈로 남긴다(지금은 두 파일에 값이 중복돼 있다).
const PAGE_WIDTH = 210;
const PAGE_HEIGHT = 297;
const MARGIN_X = 23;
const BOTTOM_LIMIT = PAGE_HEIGHT - MARGIN_X;
/** 절 제목 한 줄 높이 + 표 헤더 행 + 최소 한 줄 — 이 정도 자리가 없으면 절 전체를 다음 페이지로 넘긴다. */
const SECTION_MIN_HEIGHT = 26;

const FONT_SIZE = {
  documentTitle: 25,
  sectionTitle: 14,
  table: 10,
} as const;

const BLACK: [number, number, number] = [0, 0, 0];
/** 표 헤더·라벨 배경. exportReportToPdf.ts와 동일 계측값(#CCCCCC). */
const HEAD_FILL: [number, number, number] = [204, 204, 204];
const GRAY_HEADER_STYLES = {
  fillColor: HEAD_FILL,
  fontStyle: 'bold' as const,
  halign: 'center' as const,
};

const PT_TO_MM = 25.4 / 72;
/** 표 내부 괘선 0.36pt. */
const LINE_INNER = 0.36 * PT_TO_MM;
/** 표 외곽 테두리 1.08pt — 내부의 3배(관공서 표 인상을 만드는 대비). */
const LINE_OUTER = 1.08 * PT_TO_MM;

/** 화면 히트맵(FacilityTypeHeatmap.tsx)과 동일한 고정 행 목록 — 데이터가 0건인 카테고리도 항상 노출한다. */
const ALL_FACILITY_TYPE_CATEGORIES: FacilityTypeCategory[] = ['건물', '교량', '도로', '기타'];

function buildFileName(): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `통계리포트_${yyyy}${mm}${dd}.pdf`;
}

export interface StatisticsPdfDataParams {
  periodLabel: string;
  facilityLabel: string;
  /** 미지정 시 내보내기 시점(new Date())을 쓴다. 테스트에서 고정값을 넣기 위한 훅. */
  issuedAt?: Date;
  kpiSummary?: StatisticsKpiSummary | null;
  monthlyTrend?: MonthlyDefectTrendItem[] | null;
  defectTypeDistribution?: DefectTypeDistributionItem[] | null;
  gradeDistribution?: GradeDistributionItem[] | null;
  facilityTypeHeatmap?: FacilityTypeMonthlyHeatmapCell[] | null;
  /** 히트맵 열(월) 목록 — 화면과 동일하게 getMonthsForPeriod(period, dataMonths) 결과를 넘긴다. 생략 시 데이터에 등장하는 월만 오름차순으로 쓴다. */
  heatmapMonths?: string[];
  facilitySummary?: FacilitySummaryItem[] | null;
}

export interface StatisticsPdfTableSection {
  title: string;
  head: string[];
  rows: string[][];
}

export interface StatisticsPdfDocument {
  /** 제목 아래 조회 조건 표 — [라벨, 값] 쌍. */
  conditionRows: [string, string][];
  sections: StatisticsPdfTableSection[];
}

/**
 * 화면 값들을 PDF에 그릴 표 행으로 조립하는 순수 함수 — jsPDF/autoTable을 전혀 참조하지 않아
 * DOM·캔버스 없이 단위 테스트할 수 있다. 데이터가 없는 섹션(undefined/null/빈 배열)은 삭제한
 * exportStatisticsCsv.ts와 동일한 정책으로 통째로 생략한다(빈 표를 만들지 않음).
 */
export function buildStatisticsPdfDocument(data: StatisticsPdfDataParams): StatisticsPdfDocument {
  const conditionRows: [string, string][] = [
    ['조회 기간', data.periodLabel],
    ['시설물 범위', data.facilityLabel],
    ['출력 일시', (data.issuedAt ?? new Date()).toLocaleString('ko-KR')],
  ];

  const sections: StatisticsPdfTableSection[] = [];
  let sectionNumber = 0;
  const nextTitle = (name: string) => `${++sectionNumber}. ${name}`;

  if (data.kpiSummary) {
    const kpi = data.kpiSummary;
    sections.push({
      title: nextTitle('핵심 지표'),
      head: ['지표', '현재 값', '변화율'],
      rows: [
        ['총 탐지 하자', `${kpi.totalDefects.toLocaleString()}건`, `${kpi.totalDefectsChangeRate}%`],
        ['평균 처리일', `${kpi.avgResolutionDays}일`, `${kpi.avgResolutionDaysChangeRate}일`],
        ['조치 완료율', `${kpi.actionCompletionRate}%`, `${kpi.actionCompletionRateChangeRate}%`],
        ['진행성 하자', `${kpi.progressingDefects}건`, `${kpi.progressingDefectsChangeRate}건`],
      ],
    });
  }

  if (data.monthlyTrend && data.monthlyTrend.length > 0) {
    sections.push({
      title: nextTitle('월별 하자 추이'),
      head: ['월', '하자 건수'],
      rows: data.monthlyTrend.map((item) => [formatMonthLabel(item.month), `${item.defectCount}건`]),
    });
  }

  if (data.defectTypeDistribution && data.defectTypeDistribution.length > 0) {
    sections.push({
      title: nextTitle('AI 탐지 유형별 분포'),
      head: ['하자 유형', '탐지 건수'],
      rows: data.defectTypeDistribution.map((item) => [item.type, `${item.count}건`]),
    });
  }

  if (data.gradeDistribution && data.gradeDistribution.length > 0) {
    sections.push({
      title: nextTitle('등급별 하자 분포'),
      head: ['등급', '비율'],
      rows: data.gradeDistribution.map((item) => [`${item.grade}등급`, `${item.percent}%`]),
    });
  }

  if (data.facilityTypeHeatmap && data.facilityTypeHeatmap.length > 0) {
    const cells = data.facilityTypeHeatmap;
    const months =
      data.heatmapMonths && data.heatmapMonths.length > 0
        ? data.heatmapMonths
        : [...new Set(cells.map((cell) => cell.month))].sort();
    const findCount = (category: string, month: string) =>
      cells.find((cell) => cell.facilityTypeCategory === category && cell.month === month)?.defectCount ?? 0;

    sections.push({
      title: nextTitle('시설물군별 히트맵'),
      head: ['시설물군', ...months.map((month) => formatMonthLabel(month))],
      rows: ALL_FACILITY_TYPE_CATEGORIES.map((category) => [
        category,
        ...months.map((month) => `${findCount(category, month)}건`),
      ]),
    });
  }

  if (data.facilitySummary && data.facilitySummary.length > 0) {
    sections.push({
      title: nextTitle('시설물별 현황 요약'),
      head: ['시설물명', '시설물 유형', '총 하자수', '최근 등급', '최근 점검일'],
      rows: data.facilitySummary.map((item) => [
        item.facilityName,
        item.facilityType,
        `${item.totalDefects}건`,
        item.latestGrade ? `${item.latestGrade}등급` : '-',
        item.lastInspectedAt ?? '-',
      ]),
    });
  }

  return { conditionRows, sections };
}

export async function exportStatisticsAsPdf(params: StatisticsPdfDataParams): Promise<void> {
  const [{ default: jsPDF }, { default: autoTable }] = await Promise.all([
    import('jspdf'),
    import('jspdf-autotable'),
  ]);

  const doc = new jsPDF({ unit: 'mm', format: 'a4' });
  await registerNotoSansKrFont(doc);
  doc.setFont(PDF_FONT_NAME, 'normal');
  doc.setTextColor(...BLACK);
  doc.setDrawColor(...BLACK);
  doc.setLineHeightFactor(1.35);

  const tableDefaults = {
    theme: 'grid' as const,
    margin: { left: MARGIN_X, right: MARGIN_X, top: MARGIN_X, bottom: MARGIN_X },
    rowPageBreak: 'avoid' as const,
    showHead: 'everyPage' as const,
    tableLineColor: BLACK,
    tableLineWidth: LINE_OUTER,
    styles: {
      font: PDF_FONT_NAME,
      fontStyle: 'normal' as const,
      fontSize: FONT_SIZE.table,
      cellPadding: { top: 1.3, right: 1.6, bottom: 1.3, left: 1.6 },
      lineColor: BLACK,
      lineWidth: LINE_INNER,
      textColor: BLACK,
      valign: 'middle' as const,
      overflow: 'linebreak' as const,
    },
    headStyles: {
      font: PDF_FONT_NAME,
      ...GRAY_HEADER_STYLES,
      textColor: BLACK,
      lineWidth: LINE_INNER,
    },
  };

  /** 좌측 라벨 열 — 조회 조건 표에서 회색 헤더 관용구를 그대로 쓴다. */
  const labelColumn = (cellWidth: number) => ({ cellWidth, ...GRAY_HEADER_STYLES });

  const lastTableY = () =>
    (doc as typeof doc & { lastAutoTable: { finalY: number } }).lastAutoTable.finalY;

  /** 남은 지면이 부족하면 새 페이지로 넘긴다(절 제목이 페이지 끝에 홀로 남는 것 방지). */
  const ensureSpace = (y: number, needed: number) => {
    if (y + needed <= BOTTOM_LIMIT) return y;
    doc.addPage();
    return MARGIN_X;
  };

  const sectionTitle = (label: string, y: number) => {
    doc.setFont(PDF_FONT_NAME, 'bold');
    doc.setFontSize(FONT_SIZE.sectionTitle);
    doc.text(label, MARGIN_X, y);
    doc.setFont(PDF_FONT_NAME, 'normal');
    return y + 6;
  };

  const pdfDocument = buildStatisticsPdfDocument(params);

  doc.setFont(PDF_FONT_NAME, 'bold');
  doc.setFontSize(FONT_SIZE.documentTitle);
  doc.text('통계 리포트', PAGE_WIDTH / 2, MARGIN_X + 6, { align: 'center' });
  doc.setFont(PDF_FONT_NAME, 'normal');

  autoTable(doc, {
    ...tableDefaults,
    startY: MARGIN_X + 20,
    body: pdfDocument.conditionRows.map(([label, value]) => [label, value]),
    columnStyles: { 0: labelColumn(40), 1: { cellWidth: 'auto' } },
  });
  let y = lastTableY() + 10;

  pdfDocument.sections.forEach((section) => {
    y = ensureSpace(y, SECTION_MIN_HEIGHT);
    y = sectionTitle(section.title, y);
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      head: [section.head],
      body: section.rows,
    });
    y = lastTableY() + 10;
  });

  doc.save(buildFileName());
}
