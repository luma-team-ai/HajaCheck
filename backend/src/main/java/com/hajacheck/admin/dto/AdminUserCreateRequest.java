package com.hajacheck.admin.dto;

import com.hajacheck.auth.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 콘솔 — 사용자 등록 요청(#405, Figma node-id 1147-2649).
 * 비밀번호 확인 일치 여부는 클라이언트 검증에서 끝내고(회원가입 폼과 동일 정책), 서버로는
 * password 만 전달한다 — CompanySignupRequest 도 같은 트레이드오프.
 */
public record AdminUserCreateRequest(

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
        Role role
) {
}
