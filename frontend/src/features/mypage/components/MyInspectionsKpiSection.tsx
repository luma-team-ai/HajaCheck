import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { MyInspectionsSummary } from '../types';

type Props = {
  summary?: MyInspectionsSummary;
  isLoading: boolean;
  isError: boolean;
};

const KPI_COL_CLASS =
  'flex-1 border-r border-border px-6 first:pl-0 last:border-r-0 last:pr-0 max-[640px]:border-r-0 max-[640px]:border-b max-[640px]:pb-3 max-[640px]:last:border-b-0';

// KPI 4종(참여 점검/검수 확정/발급 보고서/진행 중) — Figma 시안대로 증감률 없는 단순 카운트라
// dashboard KpiCard(증감률 필수)를 그대로 쓰지 않고 이 feature 전용으로 단순화해 만든다(HAJA-366, #668).
export function MyInspectionsKpiSection({ summary, isLoading, isError }: Props) {
  if (isLoading) {
    return <LoadingSpinner className="flex items-center justify-start gap-2 py-2" />;
  }

  if (isError || !summary) {
    return <p className="py-2 text-sm text-danger">요약 정보를 불러오지 못했습니다.</p>;
  }

  const items = [
    { label: '참여 점검', value: `${summary.participatedCount}회차` },
    { label: '검수 확정', value: `${summary.reviewConfirmedCount}` },
    { label: '발급 보고서', value: `${summary.issuedReportCount}` },
    { label: '진행 중', value: `${summary.inProgressCount}` },
  ];

  return (
    <div className="flex flex-wrap gap-4 max-[640px]:flex-col">
      {items.map((item) => (
        <div key={item.label} className={KPI_COL_CLASS}>
          <p className="m-0 text-xs font-semibold text-text-muted">{item.label}</p>
          <p className="m-0 text-2xl font-bold text-heading">{item.value}</p>
        </div>
      ))}
    </div>
  );
}
