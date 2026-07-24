package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 서버 헬스 상태 — frontend ServerHealthStatus(monitoring.types.ts) 1:1.
 */
public enum ServerHealthStatus {
    HEALTHY,
    DEGRADED,
    DOWN
}
