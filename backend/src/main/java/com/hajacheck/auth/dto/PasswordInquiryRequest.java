package com.hajacheck.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 찾기 1단계(기업정보 인증) 요청 — 이메일 + 사업자등록번호.
 */
public record PasswordInquiryRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "사업자등록번호는 필수입니다.")
        String businessRegistrationNumber
) {
}
