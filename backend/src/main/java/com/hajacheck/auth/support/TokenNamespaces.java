package com.hajacheck.auth.support;

/**
 * TokenStore 네임스페이스 상수 — Redis 키 auth:{namespace}:{token} 의 namespace 부분.
 *
 * <p>⚠️ 네임스페이스마다 저장 키 파생이 다르다({@link TokenKeys}) — password-reset 은 sha256 해시 키,
 * signup-status 는 원문 키(기존 in-flight 토큰 호환).
 */
public final class TokenNamespaces {

    /** 가입 상태 조회 토큰(승인 대기 화면 새로고침). peek, 장기 TTL. 저장 키는 토큰 원문. */
    public static final String SIGNUP_STATUS = "signup-status";

    /**
     * 비밀번호 재설정 토큰(#194 / HAJA-172 — 이메일 링크 방식). consume(단일 사용), 단기 TTL
     * ({@code app.auth.password-reset-ttl}, 기본 10분).
     * 저장 키는 {@code sha256(token)} — 원문은 메일로만 전달된다({@link TokenKeys} 참조).
     */
    public static final String PASSWORD_RESET = "password-reset";

    private TokenNamespaces() {
    }
}
