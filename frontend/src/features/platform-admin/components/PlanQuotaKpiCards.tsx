import { PLAN_QUOTA_EMPTY_CELL } from '../planQuota.constants';
import type { PlanQuotaStats } from '../planQuota.types';

export const PLAN_QUOTA_KPI_TEST_ID = 'plan-quota-kpi';

interface PlanQuotaKpiCardsProps {
  stats?: PlanQuotaStats;
  isError: boolean;
}

// KPI 카드 2종 — Figma node-id 1206-2639. 좌: 전체 활성 사용자(보라 점), 우: 평균 쿼터 사용률(주황 점 + 바).
// data가 없어도 카드는 사라지지 않는다 — 조회 전에는 0, 실패 시에는 "-"로 자리를 지킨다(사용자 관리 카드와 동일 규칙).
export function PlanQuotaKpiCards({ stats, isError }: PlanQuotaKpiCardsProps) {
  const activeUsersText = isError
    ? PLAN_QUOTA_EMPTY_CELL
    : (stats?.activeUsers ?? 0).toLocaleString('ko-KR');
  const usagePercent = isError ? null : (stats?.totalQuotaUsagePercent ?? 0);

  return (
    <div
      className="grid grid-cols-1 gap-6 sm:grid-cols-2"
      data-testid={PLAN_QUOTA_KPI_TEST_ID}
    >
      <section className="rounded-[20px] border border-border bg-surface p-6">
        <div className="flex items-center gap-2">
          <span className="inline-block h-2 w-2 rounded-full bg-[#6366f1]" aria-hidden />
          <span className="text-[13px] font-medium text-text-muted">전체 활성 사용자</span>
        </div>
        <p className="mt-4 flex items-baseline gap-1">
          <span className="text-4xl font-bold text-heading">{activeUsersText}</span>
          <span className="text-sm text-text-muted">명</span>
        </p>
      </section>

      <section className="rounded-[20px] border border-border bg-surface p-6">
        <div className="flex items-center gap-2">
          <span className="inline-block h-2 w-2 rounded-full bg-[#f97316]" aria-hidden />
          <span className="text-[13px] font-medium text-text-muted">평균 쿼터 사용률</span>
        </div>
        <p className="mt-4">
          <span className="text-4xl font-bold text-heading">
            {usagePercent === null ? PLAN_QUOTA_EMPTY_CELL : usagePercent}
          </span>
          {usagePercent !== null && <span className="text-2xl font-bold text-heading">%</span>}
        </p>
        <div
          className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-surface-muted"
          role="progressbar"
          aria-label="전체 쿼터 사용률"
          aria-valuenow={usagePercent ?? undefined}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          {usagePercent !== null && (
            <div
              className="h-full rounded-full bg-primary"
              style={{ width: `${usagePercent}%` }}
            />
          )}
        </div>
      </section>
    </div>
  );
}
