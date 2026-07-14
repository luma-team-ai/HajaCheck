package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Company;

/**
 * 가입 상태 조회 응답(승인 대기 화면 새로고침).
 * status ∈ PENDING_REVIEW|APPROVED|REJECTED. rejectionReason 은 REJECTED 일 때만 존재.
 */
public record SignupStatusResponse(
        String status,
        String companyName,
        String rejectionReason
) {
    public static SignupStatusResponse from(Company company) {
        return new SignupStatusResponse(
                company.getStatus().name(),
                company.getName(),
                company.getRejectionReason()
        );
    }
}
