package com.hajacheck.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 찾기 2단계(재설정) 요청 — 재설정 토큰 + 새 비밀번호.
 */
public record PasswordResetRequest(

        @NotBlank(message = "재설정 토큰은 필수입니다.")
        String resetToken,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호는 영문과 숫자를 포함해야 합니다.")
        String newPassword
) {
}
