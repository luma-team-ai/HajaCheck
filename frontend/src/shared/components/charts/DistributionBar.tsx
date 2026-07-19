import { ChartEmptyState } from './ChartEmptyState';
import { CHART_COLORS } from './palette';
import { DEFAULT_CHART_EMPTY_MESSAGE } from './types';

export interface DistributionSegment {
  /** React key 및 범례 매칭용 고유 식별자 */
  key: string;
  /** 범례에 표시할 라벨 */
  label: string;
  /** 세그먼트 비율(0~100) */
  percent: number;
  /** 세그먼트 색상(raw hex) */
  color: string;
}

interface DistributionBarProps {
  segments: DistributionSegment[];
  ariaLabel: string;
  /** 바 높이(px) */
  height?: number;
  /** 범례 표시 여부 */
  showLegend?: boolean;
  /** 빈 데이터일 때 표시할 안내 문구 */
  emptyMessage?: string;
}

const DEFAULT_HEIGHT = 14;

/**
 * 등급 분포 등 "전체 대비 비율"을 하나의 가로 바로 보여주는 공용 세그먼트 바.
 * recharts를 쓰지 않는다 — 단순 비율 바는 SVG 차트보다 flex 레이아웃이 더 가볍고 정확하다
 * (features/dashboard/components/GradeDistributionCard.tsx의 기존 검증된 시각 패턴을 일반화).
 */
export function DistributionBar({
  segments,
  ariaLabel,
  height = DEFAULT_HEIGHT,
  showLegend = true,
  emptyMessage = DEFAULT_CHART_EMPTY_MESSAGE,
}: DistributionBarProps) {
  const hasRenderableValue = segments.some((segment) => segment.percent > 0);

  if (segments.length === 0 || !hasRenderableValue) {
    return <ChartEmptyState ariaLabel={ariaLabel} height={height} message={emptyMessage} />;
  }

  return (
    <div>
      <div
        className="flex w-full overflow-hidden rounded-full"
        style={{ height, backgroundColor: CHART_COLORS.track }}
        role="img"
        aria-label={ariaLabel}
      >
        {segments.map((segment) => (
          <div
            key={segment.key}
            className="h-full"
            style={{ width: `${segment.percent}%`, backgroundColor: segment.color }}
          />
        ))}
      </div>

      {showLegend && (
        <ul className="m-0 mt-3.5 flex list-none flex-wrap gap-2.5 p-0">
          {segments.map((segment) => (
            <li key={segment.key} className="flex items-center gap-1.5 text-[13px] text-text-muted">
              <span
                className="inline-block h-2 w-2 rounded-full"
                style={{ backgroundColor: segment.color }}
              />
              {segment.label} ({segment.percent}%)
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
