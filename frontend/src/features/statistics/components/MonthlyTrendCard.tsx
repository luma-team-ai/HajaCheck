import {
  Area,
  AreaChart,
  CartesianGrid,
  Dot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type ActiveDotProps,
  type DotItemDotProps,
  type LabelProps,
  type TooltipContentProps,
} from 'recharts';
import { ChartEmptyState } from '../../../shared/components/charts/ChartEmptyState';
import { DEFAULT_CHART_EMPTY_MESSAGE, DEFAULT_CHART_HEIGHT } from '../../../shared/components/charts/types';
import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { useMonthlyDefectTrend } from '../hooks/useMonthlyDefectTrend';
import type { MonthlyDefectTrendItem, StatisticsFilterParams } from '../types';
import { formatMonthLabel } from '../utils/formatMonthLabel';

// PRD §2 월별 하자 추이. Figma 시안(node 77-1454)은 영역 그라데이션 채우기 + 최고점 강조 dot +
// 검정 배지 툴팁을 요구한다 — shared LineChart 래퍼(단순 선만 지원)로는 표현할 수 없어 이 카드
// 전용으로 recharts AreaChart를 직접 구성한다.
function TrendTooltip({ active, payload }: TooltipContentProps) {
  if (!active || !payload?.length) return null;
  const value = payload[0]?.value;
  return (
    <div className="rounded bg-zinc-900 px-2 py-1 text-xs font-medium text-white">
      {typeof value === 'number' ? value.toLocaleString() : value}건
    </div>
  );
}

interface MonthlyTrendCardProps {
  filterParams?: StatisticsFilterParams;
}

export function MonthlyTrendCard({ filterParams }: MonthlyTrendCardProps) {
  const { data, isLoading, isError } = useMonthlyDefectTrend(filterParams);
  const maxCount = data && data.length > 0 ? Math.max(...data.map((item) => item.defectCount)) : null;

  // 실데이터 수치에 맞춰 Y축 도메인 및 틱을 동적으로 확장 (상단 25% 여유로 피크 배지 클리핑 방지)
  const calculatedMax = maxCount != null ? Math.max(maxCount, 100) : 400;
  const yMax = Math.max(400, Math.ceil((calculatedMax * 1.25) / 100) * 100);
  const step = Math.max(50, Math.ceil(yMax / 4 / 50) * 50);
  const ticks: number[] = [];
  for (let i = 0; i <= yMax; i += step) {
    ticks.push(i);
  }

  const renderPeakDot = (props: DotItemDotProps) => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { points, payload, index, ...rest } = props;
    const isPeak = (payload as MonthlyDefectTrendItem | undefined)?.defectCount === maxCount;
    return (
      <Dot
        key={index}
        {...rest}
        r={isPeak ? 4 : 0}
        fill="#18181b"
        stroke="#18181b"
        strokeWidth={0}
        opacity={1}
        fillOpacity={1}
      />
    );
  };

  const renderActiveDot = (props: ActiveDotProps) => {
    const { payload, index, ...rest } = props;
    const isPeak = (payload as MonthlyDefectTrendItem | undefined)?.defectCount === maxCount;
    return (
      <Dot
        key={typeof index === 'number' || typeof index === 'string' ? index : 0}
        {...rest}
        r={isPeak ? 4 : 3.5}
        fill="#18181b"
        stroke="#18181b"
        strokeWidth={0}
        opacity={1}
        fillOpacity={1}
      />
    );
  };

  // Figma는 피크 지점 값을 호버 툴팁이 아니라 항상 보이는 검정 배지로 띄운다.
  const renderPeakLabel = (props: LabelProps) => {
    const { x, y, value, index } = props as typeof props & { index?: number };
    if (x === undefined || y === undefined || index === undefined) return null;
    if ((data as MonthlyDefectTrendItem[])[index]?.defectCount !== maxCount) return null;

    const numX = Number(x);
    const numY = Number(y);
    const text = `${value}`;
    const width = text.length * 8 + 20;
    return (
      <g key="peak-label">
        <rect x={numX - width / 2} y={numY - 34} width={width} height={24} rx={5} fill="#18181b" />
        <text x={numX} y={numY - 17} textAnchor="middle" fill="#fff" fontSize={12} fontWeight={600}>
          {text}
        </text>
      </g>
    );
  };

  return (
    <section className="flex h-80 flex-col bg-white border border-zinc-200 p-6">
      <h3 className="pb-6 text-zinc-900 text-base font-medium leading-6">월별 하자 추이</h3>
      {isLoading && <LoadingSpinner />}
      {isError && <p className="dashboard-card-status">월별 추이를 불러오지 못했습니다.</p>}
      {!isLoading && !isError && (!data || data.length === 0) && (
        <ChartEmptyState
          ariaLabel="월별 하자 추이"
          height={DEFAULT_CHART_HEIGHT}
          message={DEFAULT_CHART_EMPTY_MESSAGE}
        />
      )}
      {!isLoading && !isError && data && data.length > 0 && (
        <div className="flex-1" role="img" aria-label="월별 하자 추이">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data} margin={{ top: 38, right: 28, left: 0, bottom: 0 }}>
              <defs>
                <linearGradient id="monthlyTrendFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#18181b" stopOpacity={0.18} />
                  <stop offset="100%" stopColor="#18181b" stopOpacity={0.01} />
                </linearGradient>
              </defs>
              <CartesianGrid vertical={false} stroke="#f4f4f5" />
              <XAxis
                dataKey="month"
                tickFormatter={formatMonthLabel}
                tick={{ fontSize: 12, fill: '#71717a' }}
                axisLine={{ stroke: '#e4e4e7' }}
                tickLine={false}
              />
              <YAxis
                tick={{ fontSize: 12, fill: '#a1a1aa' }}
                axisLine={false}
                tickLine={false}
                width={36}
                domain={[0, yMax]}
                ticks={ticks}
              />
              <Tooltip
                content={(props) => <TrendTooltip {...props} />}
                isAnimationActive={false}
                cursor={{ stroke: '#e4e4e7', strokeDasharray: '3 3' }}
              />
              <Area
                type="linear"
                dataKey="defectCount"
                stroke="#18181b"
                strokeWidth={1.7}
                fill="url(#monthlyTrendFill)"
                dot={renderPeakDot}
                activeDot={renderActiveDot}
                label={renderPeakLabel}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
