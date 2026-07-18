import { Cell, Legend, Pie, PieChart as RechartsPieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { ChartEmptyState } from './ChartEmptyState';
import { CHART_LEGEND_STYLE, CHART_SERIES_COLORS, CHART_TOOLTIP_STYLE } from './palette';
import type { ChartBaseProps } from './types';

interface PieChartProps<T extends object> extends ChartBaseProps {
  data: T[];
  /** 조각 값으로 사용할 필드명 */
  dataKey: keyof T & string;
  /** 조각 라벨(범례/툴팁)로 사용할 필드명 */
  nameKey: keyof T & string;
  /** 조각 색상 오버라이드 (미지정 시 CHART_SERIES_COLORS 순환 배정) */
  colors?: readonly string[];
  /** 0보다 크면 도넛 차트로 표시 */
  innerRadius?: number | string;
  /** 범례 표시 여부 */
  showLegend?: boolean;
}

const DEFAULT_HEIGHT = 300;
const DEFAULT_EMPTY_MESSAGE = '표시할 데이터가 없습니다.';

/** 공통 스타일(색상 팔레트·툴팁·폰트)이 적용된 얇은 recharts PieChart 래퍼. */
export function PieChart<T extends object>({
  data,
  dataKey,
  nameKey,
  ariaLabel,
  height = DEFAULT_HEIGHT,
  emptyMessage = DEFAULT_EMPTY_MESSAGE,
  valueFormatter,
  colors,
  innerRadius = 0,
  showLegend = true,
}: PieChartProps<T>) {
  if (data.length === 0) {
    return <ChartEmptyState ariaLabel={ariaLabel} height={height} message={emptyMessage} />;
  }

  const palette = colors && colors.length > 0 ? colors : CHART_SERIES_COLORS;

  return (
    <div className="w-full" style={{ height }} role="img" aria-label={ariaLabel}>
      <ResponsiveContainer width="100%" height="100%">
        <RechartsPieChart>
          {/* 파이/도넛류 요약 위젯은 값이 바로 눈에 들어오는 게 중요해 진입 애니메이션 없이 즉시 렌더링한다 */}
          <Pie
            data={data}
            dataKey={dataKey}
            nameKey={nameKey}
            cx="50%"
            cy="50%"
            innerRadius={innerRadius}
            outerRadius="80%"
            isAnimationActive={false}
          >
            {data.map((item, index) => (
              <Cell key={`${String(item[nameKey])}-${String(item[dataKey])}`} fill={palette[index % palette.length]} />
            ))}
          </Pie>
          <Tooltip
            {...CHART_TOOLTIP_STYLE}
            formatter={valueFormatter ? (value) => (value === undefined ? '' : valueFormatter(value)) : undefined}
          />
          {showLegend && <Legend wrapperStyle={CHART_LEGEND_STYLE} />}
        </RechartsPieChart>
      </ResponsiveContainer>
    </div>
  );
}
