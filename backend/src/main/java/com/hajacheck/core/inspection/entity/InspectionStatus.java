package com.hajacheck.core.inspection.entity;

/**
 * 점검 처리 상태 — DDL inspection_status_type(생성/업로드중/분석중/분석완료/검토완료/보고서화).
 */
public enum InspectionStatus {
    CREATED,
    UPLOADING,
    ANALYZING,
    ANALYZED,
    REVIEWED,
    REPORTED
}
