package com.hajacheck.platformadmin.dto;

/**
 * 서비스 통계(#633) 월별 요약 — frontend MonthlySummaryRow 1:1. {@code analysisCount} 는 분석 요청
 * 건수가 아니라 <b>분석한 이미지 장수</b>다(#1407 후속 — frontend 라벨 "분석 장수"와 정합).
 */
public record PlatformAdminMonthlySummaryRow(
        String month,
        long newSubscribers,
        long analysisCount,
        long counselCount,
        long upgradeConversions,
        PlatformAdminMonthlyTrend trend
) {
}
