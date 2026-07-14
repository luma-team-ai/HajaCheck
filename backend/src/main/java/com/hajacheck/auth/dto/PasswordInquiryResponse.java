package com.hajacheck.auth.dto;

/**
 * 비밀번호 찾기 1단계 응답 — 재설정 토큰(2단계 입력) + 마스킹 이메일 + 만료(초).
 */
public record PasswordInquiryResponse(
        String resetToken,
        String maskedEmail,
        long expiresInSeconds
) {
}
