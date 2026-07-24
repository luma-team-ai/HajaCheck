package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 서버 자원 사용률 — frontend ServerResourceUsage(monitoring.types.ts) 1:1.
 * Actuator MetricsEndpoint 로 조회한 현재 시점(라이브) 값이라 시계열이 아니다(PlatformAdminMonitoringService 참고).
 */
public record ServerResourceUsageResponse(
        double cpuUsagePercent,
        double memoryUsagePercent,
        double diskUsagePercent
) {
}
