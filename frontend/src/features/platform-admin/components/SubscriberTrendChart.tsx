import { LineChart } from '../../../shared/components/charts';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { SubscriberTrendPoint } from '../stats.types';

interface SubscriberTrendChartProps {
  data: SubscriberTrendPoint[];
  isLoading: boolean;
  isError: boolean;
}

export function SubscriberTrendChart({ data, isLoading, isError }: SubscriberTrendChartProps) {
  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (isError) {
    return (
      <p className="text-sm text-danger" role="alert">
        가입자 추이를 불러오지 못했습니다.
      </p>
    );
  }

  return (
    <LineChart
      data={data}
      xKey="month"
      series={[{ dataKey: 'subscribers', name: '가입자 수' }]}
      ariaLabel="최근 6개월 가입자 추이"
      showLegend={false}
    />
  );
}
