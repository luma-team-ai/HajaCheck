package com.hajacheck.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 로그인 후 비밀번호 변경 요청(#1315 / HAJA-601).
 *
 * <p><b>userId 를 받지 않는다</b> — 대상 사용자는 세션 principal({@code @AuthenticationPrincipal
 * LoginUser})로만 식별한다. 바디/파라미터로 받으면 그 자체가 IDOR(타인 비밀번호 변경) 경로가 된다.
 *
 * <p><b>newPassword 정책은 가입(CompanySignupRequest)·재설정(PasswordResetRequest)과 동일</b>해야 한다:
 * 이 경로가 느슨하면 여기가 비밀번호 정책 우회로가 된다. 검증 실패는
 * MethodArgumentNotValid → INVALID_INPUT(400) — 401 금지.
 *
 * <p>⚠️ <b>currentPassword 에는 형식 제약(@Size/@Pattern)을 걸지 않는다.</b> 검증 대상은 "형식"이 아니라
 * "일치 여부"이고, 형식 제약을 걸면 ①정책 도입 이전에 만들어진 기존 비밀번호를 쓰는 사용자가 401 이 아닌
 * 400 을 맞아 변경 자체를 못 하게 되며 ②400/401 차이가 "저장된 비밀번호가 현재 정책을 만족하는가"라는
 * 정보를 되돌려준다. 불일치는 언제나 401 AUTH_INVALID_CREDENTIALS 하나로 통일한다.
 */
public record PasswordChangeRequest(

        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호는 영문과 숫자를 포함해야 합니다.")
        String newPassword
) {
}
