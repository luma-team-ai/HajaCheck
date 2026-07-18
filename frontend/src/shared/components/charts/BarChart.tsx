import type { DataKey } from 'recharts';
import {
  Bar,
  BarChart as RechartsBarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { CHART_AXIS_TICK_STYLE, CHART_COLORS, CHART_SERIES_COLORS, CHART_TOOLTIP_STYLE } from './palette';
import type { ChartSeries } from './types';

interface BarChartProps<T extends object> {
  data: T[];
  /** X축(가로) 카테고리로 사용할 필드명 */
  xKey: keyof T & string;
  series: ChartSeries[];
  /** 차트 높이(px) — 너비는 부모 컨테이너에 맞춰 자동(ResponsiveContainer) */
  height?: number;
}

const DEFAULT_HEIGHT = 300;

/** 공통 스타일(색상 팔레트·툴팁·폰트)이 적용된 얇은 recharts BarChart 래퍼. */
export function BarChart<T extends object>({ data, xKey, series, height = DEFAULT_HEIGHT }: BarChartProps<T>) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <RechartsBarChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.border} />
        <XAxis
          // LineChart.tsx와 동일한 사유 — recharts dataKey 제네릭이 wrapper의 미확정 T에 대해
          // deferred conditional type이라 `keyof T & string`을 그대로 대입할 수 없다.
          dataKey={xKey as unknown as DataKey<T>}
          tick={CHART_AXIS_TICK_STYLE}
          axisLine={{ stroke: CHART_COLORS.border }}
          tickLine={false}
        />
        <YAxis tick={CHART_AXIS_TICK_STYLE} axisLine={{ stroke: CHART_COLORS.border }} tickLine={false} />
        <Tooltip {...CHART_TOOLTIP_STYLE} cursor={{ fill: CHART_COLORS.surfaceMuted }} />
        {series.map((s, index) => (
          <Bar
            key={s.dataKey}
            dataKey={s.dataKey}
            name={s.name ?? s.dataKey}
            fill={s.color ?? CHART_SERIES_COLORS[index % CHART_SERIES_COLORS.length]}
            radius={[4, 4, 0, 0]}
          />
        ))}
      </RechartsBarChart>
    </ResponsiveContainer>
  );
}
