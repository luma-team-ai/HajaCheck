package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Company;

/**
 * 가입 상태 조회 응답(승인 대기 화면 새로고침).
 * status ∈ PENDING_REVIEW|APPROVED|REJECTED. rejectionReason 은 REJECTED 일 때만 존재.
 *
 * <p>#1324(가입 즉시 자동승인) 이후 <b>신규 가입은 항상 APPROVED</b> 다. PENDING_REVIEW 는 V37 소급
 * 승인 이전 데이터에서만 나올 수 있고, REJECTED 는 관리자 반려 경로가 배선되면 생긴다 — 두 라벨을
 * 계약에서 지우지는 않는다(응답 스키마 축소는 프론트 파괴적 변경).
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
