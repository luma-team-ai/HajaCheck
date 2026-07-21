import {
  PLAN_QUOTA_EMPTY_CELL,
  QUOTA_BAR_NORMAL_CLASS,
  QUOTA_BAR_WARNING_CLASS,
  QUOTA_TEXT_NORMAL_CLASS,
  QUOTA_TEXT_WARNING_CLASS,
  QUOTA_WARNING_PERCENT,
} from '../planQuota.constants';
import { formatQuotaUsed, quotaPercent } from '../utils/quotaUsage';

interface QuotaUsageBarProps {
  used: number;
  limit: number | null;
  /** 접근성 라벨 접두(예: 계정명) — "{name} 쿼터 사용률"로 조합 */
  label: string;
}

// 월 분석 쿼터 사용량 바 — Figma node-id 1197-3519. 사용 장수(좌)·사용률%(우) 한 줄 + 얇은 진행 바.
// 사용률 90% 이상이면 주황으로 강조(84%는 검정, 96%는 주황 — 시안 규칙).
export function QuotaUsageBar({ used, limit, label }: QuotaUsageBarProps) {
  const percent = quotaPercent(used, limit);
  const isWarning = percent !== null && percent >= QUOTA_WARNING_PERCENT;
  const barClass = isWarning ? QUOTA_BAR_WARNING_CLASS : QUOTA_BAR_NORMAL_CLASS;
  const percentTextClass = isWarning ? QUOTA_TEXT_WARNING_CLASS : QUOTA_TEXT_NORMAL_CLASS;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-[13px]">
        <span className="font-medium text-text-default">{formatQuotaUsed(used)}</span>
        <span className={`font-semibold ${percentTextClass}`}>
          {percent === null ? PLAN_QUOTA_EMPTY_CELL : `${percent}%`}
        </span>
      </div>
      <div
        className="h-1 w-full overflow-hidden rounded-full bg-surface-muted"
        role="progressbar"
        aria-label={`${label} 쿼터 사용률`}
        aria-valuenow={percent ?? undefined}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        {percent !== null && (
          <div className={`h-full rounded-full ${barClass}`} style={{ width: `${percent}%` }} />
        )}
      </div>
    </div>
  );
}
