import type { DataKey } from 'recharts';
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart as RechartsLineChart,
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
import type { ChartBaseProps, ChartSeries } from './types';

interface LineChartProps<T extends object> extends ChartBaseProps {
  data: T[];
  /** X축(가로) 카테고리로 사용할 필드명 */
  xKey: keyof T & string;
  series: readonly ChartSeries<T>[];
  /** 배경 격자 표시 여부 */
  showGrid?: boolean;
  /** 범례 표시 여부 */
  showLegend?: boolean;
}

const DEFAULT_HEIGHT = 300;
const DEFAULT_EMPTY_MESSAGE = '표시할 데이터가 없습니다.';

/** 공통 스타일(색상 팔레트·툴팁·폰트)이 적용된 얇은 recharts LineChart 래퍼. */
export function LineChart<T extends object>({
  data,
  xKey,
  series,
  ariaLabel,
  height = DEFAULT_HEIGHT,
  emptyMessage = DEFAULT_EMPTY_MESSAGE,
  valueFormatter,
  showGrid = true,
  showLegend = true,
}: LineChartProps<T>) {
  if (data.length === 0) {
    return <ChartEmptyState ariaLabel={ariaLabel} height={height} message={emptyMessage} />;
  }

  return (
    <div className="w-full" style={{ height }} role="img" aria-label={ariaLabel}>
      <ResponsiveContainer width="100%" height="100%">
        <RechartsLineChart data={data}>
          {showGrid && <CartesianGrid strokeDasharray="3 3" stroke={CHART_COLORS.border} />}
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
          <Tooltip
            {...CHART_TOOLTIP_STYLE}
            formatter={valueFormatter ? (value) => (value === undefined ? '' : valueFormatter(value)) : undefined}
          />
          {showLegend && <Legend wrapperStyle={CHART_LEGEND_STYLE} />}
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
    </div>
  );
}
