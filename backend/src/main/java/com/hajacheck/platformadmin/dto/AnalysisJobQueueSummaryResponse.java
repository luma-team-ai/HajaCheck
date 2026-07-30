package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 큐 요약 — frontend AnalysisJobQueueSummary(monitoring.types.ts) 1:1.
 */
public record AnalysisJobQueueSummaryResponse(
        long inProgress,
        long completed,
        long failed
) {
}
