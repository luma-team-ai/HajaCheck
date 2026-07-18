import type { DataKey } from 'recharts';
import {
  CartesianGrid,
  Line,
  LineChart as RechartsLineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { CHART_AXIS_TICK_STYLE, CHART_COLORS, CHART_SERIES_COLORS, CHART_TOOLTIP_STYLE } from './palette';
import type { ChartSeries } from './types';

interface LineChartProps<T extends object> {
  data: T[];
  /** X축(가로) 카테고리로 사용할 필드명 */
  xKey: keyof T & string;
  series: ChartSeries[];
  /** 차트 높이(px) — 너비는 부모 컨테이너에 맞춰 자동(ResponsiveContainer) */
  height?: number;
}

const DEFAULT_HEIGHT = 300;

/** 공통 스타일(색상 팔레트·툴팁·폰트)이 적용된 얇은 recharts LineChart 래퍼. */
export function LineChart<T extends object>({ data, xKey, series, height = DEFAULT_HEIGHT }: LineChartProps<T>) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <RechartsLineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.border} />
        <XAxis
          // recharts의 dataKey 제네릭은 컨텍스트로 전달되는 chart data 타입과 XAxis 내부적으로
          // 다시 좁혀 비교하는데, 제네릭 T가 아직 구체화되지 않은 wrapper 내부에서는 `keyof T & string`을
          // 그 조건부 타입에 대입할 수 없다(deferred conditional type) — 런타임엔 항상 유효한 문자열 키.
          dataKey={xKey as unknown as DataKey<T>}
          tick={CHART_AXIS_TICK_STYLE}
          axisLine={{ stroke: CHART_COLORS.border }}
          tickLine={false}
        />
        <YAxis tick={CHART_AXIS_TICK_STYLE} axisLine={{ stroke: CHART_COLORS.border }} tickLine={false} />
        <Tooltip {...CHART_TOOLTIP_STYLE} />
        {series.map((s, index) => (
          <Line
            key={s.dataKey}
            type="monotone"
            dataKey={s.dataKey}
            name={s.name ?? s.dataKey}
            stroke={s.color ?? CHART_SERIES_COLORS[index % CHART_SERIES_COLORS.length]}
            strokeWidth={2}
            dot={{ r: 3 }}
            activeDot={{ r: 5 }}
          />
        ))}
      </RechartsLineChart>
    </ResponsiveContainer>
  );
}
