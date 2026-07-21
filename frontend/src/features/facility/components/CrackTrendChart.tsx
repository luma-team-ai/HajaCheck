import { LineChart } from '../../../shared/components/charts';
import type { CrackTrendPoint } from '../types';

type Props = {
  data: CrackTrendPoint[];
};

// 진행성 균열 추이 — 공용 recharts LineChart 래퍼 재사용(shared/components/charts, dev/charts/ChartShowcasePage.tsx 관례).
export function CrackTrendChart({ data }: Props) {
  return (
    <LineChart<CrackTrendPoint>
      data={data}
      xKey="cycleLabel"
      series={[{ dataKey: 'avgWidthMm', name: '평균 균열 폭(mm)' }]}
      ariaLabel="회차별 평균 균열 폭 추이 선 차트"
      valueFormatter={(value) => `${value}mm`}
    />
  );
}