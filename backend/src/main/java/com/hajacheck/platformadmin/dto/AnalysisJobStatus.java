package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 상태 — frontend AnalysisJobStatus(monitoring.types.ts) 1:1.
 *
 * <p>#1408 — {@code inspections.status} 매핑: CREATED/UPLOADING/ANALYZING → IN_PROGRESS,
 * ANALYZED/REVIEWED/REPORTED → COMPLETED. {@code FAILED}/{@code WAITING}은 DB에 대응하는 상태가
 * 아직 없어 이 매핑에서는 나오지 않는다(파이프라인에 실패 상태가 도입되면 채운다) — 계약 값 자체는
 * 프론트 호환을 위해 유지.
 */
public enum AnalysisJobStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    WAITING
}
