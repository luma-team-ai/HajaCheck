import { SERVICE_STATS_EMPTY_CELL } from '../stats.constants';
import type { ServiceStatsKpi } from '../stats.types';

export const SERVICE_STATS_KPI_TEST_ID = 'service-stats-kpi';

interface ServiceStatsKpiCardsProps {
  kpi?: ServiceStatsKpi;
  isLoading: boolean;
  isError: boolean;
}

const CARD_CLASS = 'rounded-[20px] border border-border bg-surface p-6';

// KPI 카드 4종 — Figma node-id 177-3515. 총 기업 가입자(전월 대비 증감 수)/이번 달 기업 신규(증감률)/
// 분석 요청(단위 "장")/상담 건수(증감 표기 없음) — 각기 다른 보조 표기 방식을 그대로 따른다.
// "기업" 명시: 여기 집계 대상은 회사 단위 구독(user_plans.companyId)이라 사용자 관리 화면의
// 개인 계정 수 KPI와는 다른 모수다(2026-07-24 사용자 피드백 — 두 화면 숫자가 안 맞는다는 혼선 방지).
// data가 없어도 카드는 사라지지 않는다 — 조회 전에는 0, 실패 시에는 "-"로 자리를 지킨다(PlanQuotaKpiCards와 동일 규칙).
export function ServiceStatsKpiCards({ kpi, isLoading, isError }: ServiceStatsKpiCardsProps) {
  const showEmpty = isError || isLoading;

  const totalSubscribersText = showEmpty
    ? SERVICE_STATS_EMPTY_CELL
    : (kpi?.totalSubscribers ?? 0).toLocaleString('ko-KR');
  const totalSubscribersDelta = showEmpty ? null : (kpi?.totalSubscribersDelta ?? 0);

  const newSubscribersText = showEmpty
    ? SERVICE_STATS_EMPTY_CELL
    : (kpi?.newSubscribersThisMonth ?? 0).toLocaleString('ko-KR');
  const newSubscribersChangePercent = showEmpty ? null : (kpi?.newSubscribersChangePercent ?? 0);

  const analysisRequestsText = showEmpty
    ? SERVICE_STATS_EMPTY_CELL
    : (kpi?.analysisRequests ?? 0).toLocaleString('ko-KR');

  const counselCountText = showEmpty
    ? SERVICE_STATS_EMPTY_CELL
    : (kpi?.counselCount ?? 0).toLocaleString('ko-KR');

  return (
    <div
      className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4"
      data-testid={SERVICE_STATS_KPI_TEST_ID}
    >
      <section className={CARD_CLASS}>
        <p className="m-0 text-[13px] font-medium text-text-muted">총 기업 가입자</p>
        <p className="mt-4 flex items-baseline gap-2">
          <span className="text-4xl font-bold text-heading">{totalSubscribersText}</span>
          {totalSubscribersDelta !== null && (
            <span className="text-sm font-semibold text-[#16a34a]">↑{totalSubscribersDelta}</span>
          )}
        </p>
      </section>

      <section className={CARD_CLASS}>
        <p className="m-0 text-[13px] font-medium text-text-muted">이번 달 기업 신규</p>
        <p className="mt-4 flex items-baseline gap-2">
          <span className="text-4xl font-bold text-heading">{newSubscribersText}</span>
          {newSubscribersChangePercent !== null && (
            <span className="text-sm font-semibold text-[#16a34a]">↑{newSubscribersChangePercent}%</span>
          )}
        </p>
      </section>

      <section className={CARD_CLASS}>
        <p className="m-0 text-[13px] font-medium text-text-muted">분석 요청</p>
        <p className="mt-4 flex items-baseline gap-2">
          <span className="text-4xl font-bold text-heading">{analysisRequestsText}</span>
          <span className="text-sm text-text-muted">장</span>
        </p>
      </section>

      <section className={CARD_CLASS}>
        <p className="m-0 text-[13px] font-medium text-text-muted">상담 건수</p>
        <p className="mt-4">
          <span className="text-4xl font-bold text-heading">{counselCountText}</span>
        </p>
      </section>
    </div>
  );
}
