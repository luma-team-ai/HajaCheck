package com.hajacheck.platformadmin.dto;

import com.hajacheck.auth.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 플랫폼 관리자 콘솔 — 사용자 등록 요청(#576, PR #626 후속 요구사항으로 companyId 추가).
 * AdminUserCreateRequest(#405)와 달리 companyId를 받는다 — null이면 회사 미소속(개인 계정)으로 등록.
 */
public record PlatformAdminUserCreateRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호는 영문과 숫자를 포함해야 합니다.")
        String password,

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 100, message = "이름은 100자 이하여야 합니다.")
        String name,

        @NotNull(message = "역할은 필수입니다.")
        Role role,

        /** null = 회사 미소속(개인 계정)으로 등록. */
        Long companyId
) {
}
