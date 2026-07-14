package com.hajacheck.auth.entity;

/**
 * 사업자등록번호 국세청 진위확인 상태 — DDL business_verification_status_type.
 * 라벨은 v0.3 DDL 과 정확히 일치한다. (OCR/진위확인은 현재 stub — PENDING 으로 생성만.)
 */
public enum BusinessVerificationStatus {
    PENDING,
    VERIFIED,
    FAILED
}
