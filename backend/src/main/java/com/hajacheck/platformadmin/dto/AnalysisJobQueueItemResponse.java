package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 큐 항목 — frontend AnalysisJobQueueItem(monitoring.types.ts) 1:1.
 * #1408 — {@code inspections} 테이블 기준 실데이터(PlatformAdminMonitoringService#getJobQueue 참고).
 *
 * @param facilityAddress 시설물 위치({@code facilities.address}) — 시설물명이 아니라 주소 텍스트.
 * @param durationLabel 소요 시간("mm:ss") — 진행 중이거나 완료 후 진행률 캐시가 만료(TTL 6h)돼 계산할 수 없으면 null.
 */
public record AnalysisJobQueueItemResponse(
        String id,
        String facilityAddress,
        int imageCount,
        AnalysisJobStatus status,
        String durationLabel,
        String recordedAt
) {
}
