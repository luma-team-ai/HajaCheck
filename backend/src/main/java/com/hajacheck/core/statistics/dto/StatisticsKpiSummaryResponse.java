package com.hajacheck.core.statistics.dto;

public record StatisticsKpiSummaryResponse(
        long totalDefects,
        double totalDefectsChangeRate,
        double avgResolutionDays,
        double avgResolutionDaysChangeRate,
        double actionCompletionRate,
        double actionCompletionRateChangeRate,
        long progressingDefects,
        double progressingDefectsChangeRate
) {
}
