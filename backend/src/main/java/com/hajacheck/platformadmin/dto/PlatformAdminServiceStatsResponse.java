package com.hajacheck.platformadmin.dto;

import java.util.List;

/**
 * GET /api/platform-admin/stats 응답 — frontend ServiceStatsResponse(stats.types.ts) 1:1(#633/#634).
 * companyId 스코프 없이 플랫폼 전체를 집계한다.
 */
public record PlatformAdminServiceStatsResponse(
        PlatformAdminServiceStatsKpi kpi,
        List<PlatformAdminSubscriberTrendPoint> subscriberTrend,
        List<PlatformAdminAnalysisRequestTrendPoint> analysisRequestTrend,
        List<PlatformAdminPlanDistributionItem> planDistribution,
        List<PlatformAdminCounselTypeDistributionItem> counselTypeDistribution,
        List<PlatformAdminMonthlySummaryRow> monthlySummary
) {
}
