package com.hajacheck.auth.entity;

/**
 * 기업 멤버십의 초대·승인·회수·만료 상태.
 * PostgreSQL {@code company_membership_status_type} 라벨과 정확히 일치해야 한다.
 */
public enum CompanyMembershipStatus {
    PENDING,
    APPROVED,
    REJECTED,
    REVOKED,
    EXPIRED
}
