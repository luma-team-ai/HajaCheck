import { useDashboardSummary } from '../hooks/useDashboardSummary';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { KpiCard } from './KpiCard';

// dashboard-card의 기본 padding(20px 24px)을 대신해 시안값(4px 0)을 강제 적용 —
// 공용 shared/styles/layout.css의 동일 클래스보다 이 규칙을 확실히 우선시키기 위해
// Tailwind !important 접두를 사용(원본 CSS도 소스 순서로 동일 목적의 override였음).
const KPI_CARD_GROUP_CLASS =
  'grid grid-cols-4 py-1! px-0! max-[1100px]:grid-cols-2 max-[720px]:grid-cols-1';

export function KpiSection() {
  const { data, isLoading, isError } = useDashboardSummary();

  if (isLoading) return <LoadingSpinner />;
  if (isError || !data) return <p className="dashboard-card-status">요약 정보를 불러오지 못했습니다.</p>;

  return (
    <div className={`dashboard-card ${KPI_CARD_GROUP_CLASS}`}>
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
