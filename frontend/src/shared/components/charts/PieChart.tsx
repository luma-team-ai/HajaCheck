import { Cell, Legend, Pie, PieChart as RechartsPieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { CHART_COLORS, CHART_FONT_FAMILY, CHART_SERIES_COLORS, CHART_TOOLTIP_STYLE } from './palette';

interface PieChartProps<T extends object> {
  data: T[];
  /** 조각 값으로 사용할 필드명 */
  dataKey: keyof T & string;
  /** 조각 라벨(범례/툴팁)로 사용할 필드명 */
  nameKey: keyof T & string;
  /** 차트 높이(px) — 너비는 부모 컨테이너에 맞춰 자동(ResponsiveContainer) */
  height?: number;
  /** 조각 색상 오버라이드 (미지정 시 CHART_SERIES_COLORS 순환 배정) */
  colors?: readonly string[];
}

const DEFAULT_HEIGHT = 300;

/** 공통 스타일(색상 팔레트·툴팁·폰트)이 적용된 얇은 recharts PieChart 래퍼. */
export function PieChart<T extends object>({
  data,
  dataKey,
  nameKey,
  height = DEFAULT_HEIGHT,
  colors,
}: PieChartProps<T>) {
  const palette = colors ?? CHART_SERIES_COLORS;

  return (
    <ResponsiveContainer width="100%" height={height}>
      <RechartsPieChart>
        {/* 파이/도넛류 요약 위젯은 값이 바로 눈에 들어오는 게 중요해 진입 애니메이션 없이 즉시 렌더링한다 */}
        <Pie
          data={data}
          dataKey={dataKey}
          nameKey={nameKey}
          cx="50%"
          cy="50%"
          outerRadius="80%"
          isAnimationActive={false}
        >
          {data.map((_, index) => (
            // 조각은 순서만 있고 별도 id가 없어 index를 key로 사용 (Pagination의 DOTS 처리와 동일 패턴)
            <Cell key={`cell-${index}`} fill={palette[index % palette.length]} />
          ))}
        </Pie>
        <Tooltip {...CHART_TOOLTIP_STYLE} />
        <Legend wrapperStyle={{ fontFamily: CHART_FONT_FAMILY, fontSize: 12, color: CHART_COLORS.textDefault }} />
      </RechartsPieChart>
    </ResponsiveContainer>
  );
}
