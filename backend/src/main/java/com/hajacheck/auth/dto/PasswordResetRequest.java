package com.hajacheck.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 2단계 요청 — 메일 링크의 토큰 + 새 비밀번호(#194).
 *
 * <p>비밀번호 정책은 <b>가입(CompanySignupRequest)과 동일</b>해야 한다: 재설정 경로가 느슨하면 그쪽이
 * 정책 우회로가 된다. 검증 실패는 BindException/MethodArgumentNotValid → INVALID_INPUT(400) — 401 금지.
 */
public record PasswordResetRequest(

        @NotBlank(message = "토큰은 필수입니다.")
        String token,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호는 영문과 숫자를 포함해야 합니다.")
        String newPassword
) {
}
