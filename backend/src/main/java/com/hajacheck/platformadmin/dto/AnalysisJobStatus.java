package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 분석 잡 상태 — frontend AnalysisJobStatus(monitoring.types.ts) 1:1.
 *
 * <p>#1408 — {@code inspections.status} 매핑: CREATED/UPLOADING/ANALYZING → IN_PROGRESS,
 * ANALYZED/REVIEWED/REPORTED → COMPLETED, {@code InspectionStatus.FAILED} → 이 {@code FAILED}
 * (PR머신 리뷰 5차 P2 — 파이프라인에 실패 상태가 실제로 도입돼 채웠다). {@code WAITING}은 여전히
 * DB에 대응하는 상태가 없어 나오지 않는다 — 계약 값 자체는 프론트 호환을 위해 유지.
 */
public enum AnalysisJobStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    WAITING
}
