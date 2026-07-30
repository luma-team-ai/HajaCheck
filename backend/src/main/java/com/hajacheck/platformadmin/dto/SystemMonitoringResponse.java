package com.hajacheck.platformadmin.dto;

import java.util.List;

/**
 * GET /api/platform-admin/monitoring 응답 — frontend SystemMonitoringResponse(monitoring.types.ts) 1:1(#728).
 * companyId 스코프 없이 플랫폼 전체 인프라 상태를 다룬다.
 */
public record SystemMonitoringResponse(
        List<ServerHealthItemResponse> serverHealth,
        AnalysisJobQueueResponse jobQueue,
        ServerResourceUsageResponse resourceUsage,
        List<ErrorLogItemResponse> errorLogs
) {
}
