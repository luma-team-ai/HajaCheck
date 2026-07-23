import { DistributionBar } from '../../../shared/components/charts';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { PLAN_DISTRIBUTION_COLOR, PLAN_DISTRIBUTION_LABEL } from '../stats.constants';
import type { PlanDistributionItem } from '../stats.types';

interface PlanDistributionChartProps {
  data: PlanDistributionItem[];
  isLoading: boolean;
  isError: boolean;
}

export function PlanDistributionChart({ data, isLoading, isError }: PlanDistributionChartProps) {
  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (isError) {
    return (
      <p className="text-sm text-danger" role="alert">
        플랜 분포를 불러오지 못했습니다.
      </p>
    );
  }

  const segments = data.map((item) => ({
    key: item.plan,
    label: PLAN_DISTRIBUTION_LABEL[item.plan],
    percent: item.percent,
    color: PLAN_DISTRIBUTION_COLOR[item.plan],
  }));

  return <DistributionBar segments={segments} ariaLabel="플랜별 가입자 분포" />;
}
