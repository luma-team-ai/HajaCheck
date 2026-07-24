import type { HfApiUsage } from '../monitoring.types';
import { HfApiWeeklyUsageChart } from './HfApiWeeklyUsageChart';

export const HF_API_USAGE_TEST_ID = 'hf-api-usage-card';

interface HfApiUsageCardProps {
  usage?: HfApiUsage;
  isLoading: boolean;
  isError: boolean;
}

// HF API 사용량 카드 — Figma node-id 1-404. 주간 사용량 막대 + 예산 사용률 바(80% 한도 마커) + 경고 배너.
export function HfApiUsageCard({ usage, isLoading, isError }: HfApiUsageCardProps) {
  const showEmpty = isLoading || isError;
  const usedPercent = showEmpty ? 0 : (usage?.budgetUsedPercent ?? 0);
  const limitPercent = showEmpty ? 80 : (usage?.budgetLimitPercent ?? 80);
  const isOverLimit = !showEmpty && usedPercent >= limitPercent;

  return (
    <section
      className="flex flex-col gap-5 rounded-[20px] border border-border bg-surface p-6"
      data-testid={HF_API_USAGE_TEST_ID}
    >
      <div className="flex items-center justify-between">
        <h3 className="m-0 text-base font-bold text-heading">HF API 사용량</h3>
        <span className="text-[13px] font-medium text-text-muted">이번 주</span>
      </div>

      {isLoading && <p className="text-sm text-text-muted">불러오는 중...</p>}
      {!isLoading && isError && (
        <p className="text-sm text-danger" role="alert">
          HF API 사용량을 불러오지 못했습니다.
        </p>
      )}

      {!isLoading && !isError && <HfApiWeeklyUsageChart data={usage?.weeklyUsage ?? []} />}

      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between text-sm">
          <span className="font-semibold text-heading">주간 예산</span>
          <span className={`font-bold ${isOverLimit ? 'text-danger' : 'text-heading'}`}>
            {showEmpty ? '-' : `${usedPercent}%`}
          </span>
        </div>
        <div className="relative h-2 w-full overflow-hidden rounded-full bg-[#d4d4d8]">
          <div
            className={`h-full rounded-full ${isOverLimit ? 'bg-danger' : 'bg-primary'}`}
            style={{ width: `${Math.min(usedPercent, 100)}%` }}
          />
          <div
            className="absolute top-0 h-full w-0.5 bg-[#f97316]"
            style={{ left: `${limitPercent}%` }}
            aria-hidden
          />
        </div>
        <div className="flex items-center justify-between text-xs text-text-muted">
          <span>0</span>
          <span>{limitPercent}% Limit</span>
          <span>100</span>
        </div>
      </div>

      {!showEmpty && usage?.warningMessage && (
        <div className="flex items-start gap-2 rounded-xl bg-warning-soft-bg px-4 py-3 text-[13px] text-warning-soft-fg">
          <span className="mt-1 inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-danger" />
          <span>{usage.warningMessage}</span>
        </div>
      )}
    </section>
  );
}
