package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Company;
import java.time.Instant;

/** 기업 가입 승인/반려 응답(#363) — 처리 후 회사 상태를 그대로 반환한다. */
public record CompanyApprovalResponse(
        Long companyId,
        String name,
        String status,
        Instant reviewedAt,
        String rejectionReason) {

    public static CompanyApprovalResponse from(Company company) {
        return new CompanyApprovalResponse(
                company.getId(),
                company.getName(),
                company.getStatus().name(),
                company.getReviewedAt(),
                company.getRejectionReason());
    }
}
