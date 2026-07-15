package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.global.util.EmailMasker;

/**
 * 기업 회원가입 성공 응답. signupToken 은 승인 대기 화면 상태조회용 불투명 토큰(PK 비노출).
 */
public record CompanySignupResponse(
        Long companyId,
        String maskedEmail,
        String status,
        String signupToken
) {
    public static CompanySignupResponse from(Company company, String rawEmail, String signupToken) {
        return new CompanySignupResponse(
                company.getId(),
                EmailMasker.mask(rawEmail),
                company.getStatus().name(),
                signupToken
        );
    }
}
