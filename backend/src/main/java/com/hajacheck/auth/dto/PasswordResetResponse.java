package com.hajacheck.auth.dto;

/**
 * 비밀번호 재설정 2단계 응답. 실패는 AUTH_RESET_TOKEN_INVALID(400) 로 통일한다
 * (무효/만료/사용됨을 구분해 노출하면 토큰 상태 열거 단서가 된다).
 */
public record PasswordResetResponse(boolean reset) {

    public static PasswordResetResponse done() {
        return new PasswordResetResponse(true);
    }
}
