import type { DataKey } from 'recharts';
import {
  Bar,
  BarChart as RechartsBarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { ChartEmptyState } from './ChartEmptyState';
import {
  CHART_AXIS_TICK_STYLE,
  CHART_COLORS,
  CHART_LEGEND_STYLE,
  CHART_SERIES_COLORS,
  CHART_TOOLTIP_STYLE,
} from './palette';
import {
  DEFAULT_CHART_EMPTY_MESSAGE,
  DEFAULT_CHART_HEIGHT,
  wrapTooltipFormatter,
  type ChartBaseProps,
  type ChartCategoryKey,
  type ChartSeries,
} from './types';

interface BarChartProps<T extends object> extends ChartBaseProps {
  data: T[];
  /** X축(가로) 카테고리로 사용할 필드명 */
  xKey: ChartCategoryKey<T>;
  series: readonly ChartSeries<T>[];
  /** 배경 격자 표시 여부 */
  showGrid?: boolean;
  /** 범례 표시 여부 */
  showLegend?: boolean;
}

/** 공통 스타일(색상 팔레트·툴팁·폰트)이 적용된 얇은 recharts BarChart 래퍼. */
export function BarChart<T extends object>({
  data,
  xKey,
  series,
  ariaLabel,
  height = DEFAULT_CHART_HEIGHT,
  emptyMessage = DEFAULT_CHART_EMPTY_MESSAGE,
  valueFormatter,
  showGrid = true,
  showLegend = true,
}: BarChartProps<T>) {
  if (data.length === 0) {
    return <ChartEmptyState ariaLabel={ariaLabel} height={height} message={emptyMessage} />;
  }

  return (
    <div className="w-full" style={{ height }} role="img" aria-label={ariaLabel}>
      <ResponsiveContainer width="100%" height="100%">
        <RechartsBarChart data={data}>
          {showGrid && <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.border} />}
          <XAxis
            // LineChart.tsx와 동일한 사유 — recharts dataKey 제네릭이 wrapper의 미확정 T에 대해
            // deferred conditional type이라 `keyof T & string`을 그대로 대입할 수 없다.
            dataKey={xKey as unknown as DataKey<T>}
            tick={CHART_AXIS_TICK_STYLE}
            axisLine={{ stroke: CHART_COLORS.border }}
            tickLine={false}
          />
          <YAxis tick={CHART_AXIS_TICK_STYLE} axisLine={{ stroke: CHART_COLORS.border }} tickLine={false} />
          <Tooltip
            {...CHART_TOOLTIP_STYLE}
            cursor={{ fill: CHART_COLORS.surfaceMuted }}
            formatter={wrapTooltipFormatter(valueFormatter)}
          />
          {showLegend && <Legend wrapperStyle={CHART_LEGEND_STYLE} />}
          {series.map((s, index) => (
            <Bar
              key={s.dataKey}
              dataKey={s.dataKey as unknown as DataKey<T>}
              name={s.name ?? s.dataKey}
              fill={s.color ?? CHART_SERIES_COLORS[index % CHART_SERIES_COLORS.length]}
              radius={[4, 4, 0, 0]}
            />
          ))}
        </RechartsBarChart>
      </ResponsiveContainer>
    </div>
  );
}
