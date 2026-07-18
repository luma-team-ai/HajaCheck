package com.hajacheck.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 재설정 1단계 요청 — 재설정 링크 발송 요청(#194).
 *
 * <p>이메일만 받는다. 최초 설계는 이메일 + 사업자번호를 받아 토큰을 응답에 돌려줬는데, 둘 다 준공개 정보라
 * <b>누구나 타인 계정을 탈취</b>할 수 있어 P1 반려됐다. 이 방식의 안전성은 입력값이 아니라
 * <b>메일함 소유 증명</b>에서 나오므로, 추가 입력을 받아도 보안이 올라가지 않는다.
 */
public record PasswordResetLinkRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email
) {
}
