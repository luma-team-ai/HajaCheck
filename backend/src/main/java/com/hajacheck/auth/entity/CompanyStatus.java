package com.hajacheck.auth.entity;

/**
 * 기업 회원가입 관리자 승인 상태 — DDL company_status_type.
 * 라벨은 v0.3 DDL(company_status_type) 과 정확히 일치한다.
 */
public enum CompanyStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
