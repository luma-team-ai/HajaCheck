import { useDashboardSummary } from '../hooks/useDashboardSummary';
import { KpiCard } from './KpiCard';

export function KpiSection() {
  const { data, isLoading, isError } = useDashboardSummary();

  if (isLoading) return <p className="dashboard-card-status">불러오는 중...</p>;
  if (isError || !data) return <p className="dashboard-card-status">요약 정보를 불러오지 못했습니다.</p>;

  return (
    <div className="kpi-grid">
      <KpiCard
        label="전체 시설물"
        value={`${data.totalFacilities}개`}
        changeRate={data.totalFacilitiesChangeRate}
      />
      <KpiCard
        label="이번 달 분석"
        value={`${data.monthlyAnalyzed.toLocaleString()}장`}
        changeRate={data.monthlyAnalyzedChangeRate}
      />
      <KpiCard
        label="검수 대기"
        value={`${data.pendingReview}건`}
        changeRate={data.pendingReviewChangeRate}
        hasAlertDot
      />
      <KpiCard
        label="조치 대기"
        value={`${data.pendingAction}건`}
        changeRate={data.pendingActionChangeRate}
        hasAlertDot
      />
    </div>
  );
}
