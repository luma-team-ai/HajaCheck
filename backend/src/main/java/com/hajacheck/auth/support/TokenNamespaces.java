package com.hajacheck.auth.support;

/**
 * TokenStore 네임스페이스 상수 — Redis 키 auth:{namespace}:{token} 의 namespace 부분.
 */
public final class TokenNamespaces {

    /** 가입 상태 조회 토큰(승인 대기 화면 새로고침). peek, 장기 TTL. */
    public static final String SIGNUP_STATUS = "signup-status";

    /** 비밀번호 재설정 토큰. consume(단일 사용), 단기 TTL. */
    public static final String PASSWORD_RESET = "password-reset";

    private TokenNamespaces() {
    }
}
