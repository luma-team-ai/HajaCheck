import { BarChart } from '../../../shared/components/charts';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import type { AnalysisRequestTrendPoint } from '../stats.types';

interface AnalysisRequestTrendChartProps {
  data: AnalysisRequestTrendPoint[];
  isLoading: boolean;
  isError: boolean;
}

export function AnalysisRequestTrendChart({ data, isLoading, isError }: AnalysisRequestTrendChartProps) {
  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (isError) {
    return (
      <p className="text-sm text-danger" role="alert">
        분석 요청 추이를 불러오지 못했습니다.
      </p>
    );
  }

  return (
    <BarChart
      data={data}
      xKey="month"
      series={[{ dataKey: 'requests', name: '분석 요청 장수' }]}
      ariaLabel="최근 6개월 분석 요청 추이"
      showLegend={false}
    />
  );
}
