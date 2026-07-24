import { USAGE_BAR_FILL_CLASS, USAGE_WARNING_BADGE_CLASS } from '../statusClasses';
import { formatLimit, isUsageWarning, usagePercent } from '../utils/planFormat';

type Props = {
  label: string;
  used: number;
  limit: number | null;
  unit?: string;
  /** 월 분석 항목 전용(Figma 리디자인, #712) — 경고 배지 문구에 "매월 1일 초기화" 안내를 덧붙인다. */
  resetMonthly?: boolean;
};

export function UsageBar({ label, used, limit, unit = '', resetMonthly = false }: Props) {
  const percent = usagePercent(used, limit);
  const warning = isUsageWarning(percent);
  const limitText = limit === null ? formatLimit(limit) : `${formatLimit(limit)}${unit}`;

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span className="flex items-center gap-2 text-sm font-semibold text-text-default">
          {label}
          {warning && (
            <span
              className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${USAGE_WARNING_BADGE_CLASS}`}
            >
              <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
              {percent}% 도달{resetMonthly ? ' · 매월 1일 초기화' : ''}
            </span>
          )}
        </span>
        <span className="flex items-baseline gap-1 whitespace-nowrap">
          <span className="text-2xl font-semibold text-heading">
            {used.toLocaleString()}
            {unit}
          </span>
          <span className="text-xs text-text-muted">/{limitText}</span>
        </span>
      </div>
      <div
        className="h-1.5 w-full overflow-hidden rounded-full bg-neutral-100"
        role="progressbar"
        aria-valuenow={percent ?? 0}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`${label} 사용량 ${percent ?? 0}%`}
      >
        <div
          className={`h-full rounded-full ${warning ? USAGE_BAR_FILL_CLASS.warning : USAGE_BAR_FILL_CLASS.normal}`}
          style={{ width: `${percent ?? 0}%` }}
        />
      </div>
    </div>
  );
}
