package com.hajacheck.core.defect.entity;

/**
 * 결함 조치 상태 — DDL defect_status_type(탐지됨/확인됨/조치중/해결됨).
 *
 * <p>CONFIRMED(검수확정)가 "검수완료 + 아직 조치 착수 전"까지 겸한다 — 구 ACTION_PENDING(조치대기)은
 * CONFIRMED로 흡수되어 제거됐다(V19 마이그레이션).
 */
public enum DefectStatus {
    DETECTED,
    CONFIRMED,
    IN_PROGRESS,
    RESOLVED
}
