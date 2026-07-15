package com.hajacheck.core.defect.entity;

/**
 * 결함 조치 상태 — DDL defect_status_type(탐지됨/확인됨/조치대기/조치중/해결됨).
 */
public enum DefectStatus {
    DETECTED,
    CONFIRMED,
    ACTION_PENDING,
    IN_PROGRESS,
    RESOLVED
}
