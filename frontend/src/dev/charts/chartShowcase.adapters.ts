import type {
  DefectGrade,
  GradeDistributionItem,
  InspectionStatus,
  RecentInspectionItem,
} from '../../features/dashboard/types';

export interface InspectionTrendChartItem {
  inspectedAt: string;
  defectCount: number;
}

export interface GradeDistributionChartItem {
  grade: DefectGrade;
  percent: number;
}

export interface StatusDistributionChartItem {
  status: InspectionStatus;
  count: number;
}

const INSPECTION_STATUS_ORDER: readonly InspectionStatus[] = ['분석중', '검수대기', '조치대기', '완료'];

export function toInspectionTrendChartData(items: readonly RecentInspectionItem[]): InspectionTrendChartItem[] {
  const defectCountByDate = new Map<string, number>();

  items.forEach(({ inspectedAt, defectCount }) => {
    defectCountByDate.set(inspectedAt, (defectCountByDate.get(inspectedAt) ?? 0) + defectCount);
  });

  return [...defectCountByDate]
    .sort(([dateA], [dateB]) => dateA.localeCompare(dateB))
    .map(([inspectedAt, defectCount]) => ({ inspectedAt, defectCount }));
}

export function toGradeDistributionChartData(items: readonly GradeDistributionItem[]): GradeDistributionChartItem[] {
  return items.map(({ grade, percent }) => ({ grade, percent }));
}

export function toStatusDistributionChartData(items: readonly RecentInspectionItem[]): StatusDistributionChartItem[] {
  const counts = new Map<InspectionStatus, number>();

  items.forEach(({ status }) => {
    counts.set(status, (counts.get(status) ?? 0) + 1);
  });

  return INSPECTION_STATUS_ORDER.filter((status) => counts.has(status)).map((status) => ({
    status,
    count: counts.get(status) ?? 0,
  }));
}
