package com.hajacheck.platformadmin.dto;

/** 서비스 통계(#633) 월별 요약 — frontend MonthlySummaryRow 1:1. */
public record PlatformAdminMonthlySummaryRow(
        String month,
        long newSubscribers,
        long analysisCount,
        long counselCount,
        long freeToStandardConversions,
        PlatformAdminMonthlyTrend trend
) {
}
