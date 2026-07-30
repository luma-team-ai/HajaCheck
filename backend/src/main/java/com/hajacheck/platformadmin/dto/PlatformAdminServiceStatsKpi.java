package com.hajacheck.platformadmin.dto;

/**
 * 서비스 통계(#633) KPI 4종 — frontend ServiceStatsKpi 1:1.
 *
 * <p>analysisRequests/counselCount 는 "이번 기간"(현재 트렌드 윈도우, 최근 6개월) 누적치다 — monthlySummary
 * 각 행의 analysisCount/counselCount 합계와 항상 일치한다(PlatformAdminServiceStatsService 계약).
 */
public record PlatformAdminServiceStatsKpi(
        long totalSubscribers,
        long totalSubscribersDelta,
        long newSubscribersThisMonth,
        int newSubscribersChangePercent,
        long analysisRequests,
        long counselCount
) {
}
