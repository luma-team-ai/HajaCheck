package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 상태 — frontend AnalysisJobStatus(monitoring.types.ts) 1:1.
 *
 * <p>이 버전에서는 실제로 값을 채우는 곳이 없다(PlatformAdminMonitoringService#getJobQueue 참고 —
 * AI 자동 분석 파이프라인이 아직 없어 항상 빈 목록을 반환한다) — 파이프라인 도입 시 사용할 계약만 미리 정의.
 */
public enum AnalysisJobStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    WAITING
}
