import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis } from 'recharts';
import { ChartEmptyState } from '../../../shared/components/charts/ChartEmptyState';
import { CHART_AXIS_TICK_STYLE, CHART_COLORS, CHART_TOOLTIP_STYLE } from '../../../shared/components/charts/palette';
import { DEFAULT_CHART_EMPTY_MESSAGE } from '../../../shared/components/charts/types';
import type { HfApiWeeklyUsagePoint } from '../monitoring.types';

interface HfApiWeeklyUsageChartProps {
  data: HfApiWeeklyUsagePoint[];
  height?: number;
}

const CHART_HEIGHT = 160;

// 이번 주 HF API 사용량 막대 차트 — Figma node-id 1-404. 사용량이 가장 많은 날(=오늘)만 짙은 색으로
// 강조하고 나머지는 옅은 회색으로 낮춘다. 공용 BarChart 래퍼는 시리즈 단위 단색만 지원해 막대별
// 색상 오버라이드(Cell)가 필요한 이 화면엔 맞지 않아, palette 상수만 재사용하는 전용 컴포넌트로 둔다.
export function HfApiWeeklyUsageChart({ data, height = CHART_HEIGHT }: HfApiWeeklyUsageChartProps) {
  const ariaLabel = '이번 주 HF API 사용량';
  const hasUsage = data.some((point) => point.usage > 0);

  if (data.length === 0 || !hasUsage) {
    return <ChartEmptyState ariaLabel={ariaLabel} height={height} message={DEFAULT_CHART_EMPTY_MESSAGE} />;
  }

  const peakUsage = Math.max(...data.map((point) => point.usage));

  return (
    <div className="w-full" style={{ height }} role="img" aria-label={ariaLabel}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data}>
          <XAxis dataKey="day" tick={CHART_AXIS_TICK_STYLE} axisLine={{ stroke: CHART_COLORS.border }} tickLine={false} />
          <Tooltip {...CHART_TOOLTIP_STYLE} cursor={{ fill: CHART_COLORS.surfaceMuted }} />
          <Bar dataKey="usage" name="사용량" radius={[4, 4, 0, 0]}>
            {data.map((point) => (
              <Cell
                key={point.day}
                fill={point.usage === peakUsage ? CHART_COLORS.primary : CHART_COLORS.border}
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
