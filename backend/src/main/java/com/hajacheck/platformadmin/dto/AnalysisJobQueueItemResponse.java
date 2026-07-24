package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 큐 항목 — frontend AnalysisJobQueueItem(monitoring.types.ts) 1:1.
 * AnalysisJobStatus 와 동일하게, 실제 AI 자동 분석 파이프라인 도입 전까지는 값을 채우는 곳이 없다.
 *
 * @param durationLabel 소요 시간("mm:ss") — 대기 상태 등 아직 없으면 null.
 */
public record AnalysisJobQueueItemResponse(
        String id,
        String facilityName,
        int imageCount,
        AnalysisJobStatus status,
        String durationLabel,
        String recordedAt
) {
}
