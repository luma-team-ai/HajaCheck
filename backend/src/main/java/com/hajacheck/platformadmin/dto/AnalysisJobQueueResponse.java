package com.hajacheck.platformadmin.dto;

import java.util.List;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 큐 — frontend AnalysisJobQueue(monitoring.types.ts) 1:1.
 *
 * <p>이 이슈 범위에서는 항상 {@code summary} 전부 0, {@code jobs} 빈 목록을 반환한다(PlatformAdminMonitoringService
 * #getJobQueue) — 이미지 기반 AI 자동 분석 파이프라인이 아직 코드베이스에 없어(DefectExplainRequest 는
 * 사람이 등록한 결함 1건의 설명 텍스트만 생성) 채울 실제 데이터가 없다. 없는 데이터를 지어내지 않고 실제
 * 상태(아직 없음)를 정직하게 반환한다 — 파이프라인 도입 후 별도 이슈에서 채운다.
 */
public record AnalysisJobQueueResponse(
        AnalysisJobQueueSummaryResponse summary,
        List<AnalysisJobQueueItemResponse> jobs
) {
    public static AnalysisJobQueueResponse empty() {
        return new AnalysisJobQueueResponse(new AnalysisJobQueueSummaryResponse(0, 0, 0), List.of());
    }
}
