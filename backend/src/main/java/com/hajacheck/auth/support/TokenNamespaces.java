package com.hajacheck.auth.support;

/**
 * TokenStore 네임스페이스 상수 — Redis 키 auth:{namespace}:{token} 의 namespace 부분.
 */
public final class TokenNamespaces {

    /** 가입 상태 조회 토큰(승인 대기 화면 새로고침). peek, 장기 TTL. */
    public static final String SIGNUP_STATUS = "signup-status";

    /**
     * 비밀번호 재설정 토큰. consume(단일 사용), 단기 TTL.
     * ⚠️ 현재 미사용 — 비밀번호 찾기 엔드포인트가 P1 로 제외됨. 보안질문 방식 후속에서 재사용 예정(#194 / HAJA-172).
     */
    public static final String PASSWORD_RESET = "password-reset";

    private TokenNamespaces() {
    }
}
