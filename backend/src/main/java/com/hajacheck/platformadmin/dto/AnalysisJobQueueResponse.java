package com.hajacheck.platformadmin.dto;

import java.util.List;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 큐 — frontend AnalysisJobQueue(monitoring.types.ts) 1:1.
 *
 * <p>#1408 — {@code inspections} 테이블 기준 최근 N건 실데이터를 반환한다(PlatformAdminMonitoringService
 * #getJobQueue). {@code empty()}는 예외적 폴백용으로만 남겨둔다.
 */
public record AnalysisJobQueueResponse(
        AnalysisJobQueueSummaryResponse summary,
        List<AnalysisJobQueueItemResponse> jobs
) {
    public static AnalysisJobQueueResponse empty() {
        return new AnalysisJobQueueResponse(new AnalysisJobQueueSummaryResponse(0, 0, 0), List.of());
    }
}
