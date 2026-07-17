package com.hajacheck.auth.support;

/**
 * 토큰 네임스페이스 상수 — Redis 키 {@code auth:{namespace}:{저장토큰}} 의 namespace 부분.
 */
public final class TokenNamespaces {

    /** 가입 상태 조회 토큰(승인 대기 화면 새로고침). {@link TokenStore} 소유. peek, 장기 TTL, 저장 키는 원문. */
    public static final String SIGNUP_STATUS = "signup-status";

    /**
     * 비밀번호 재설정 토큰(#194 / HAJA-172 — 이메일 링크 방식). 단일 사용, 단기 TTL
     * ({@code app.auth.password-reset-ttl}, 기본 10분), 저장 키는 {@code sha256(token)}.
     *
     * <p>⚠️ 이 네임스페이스는 {@link TokenStore} 가 아니라 <b>{@link PasswordResetTokenStore} 가 소유</b>한다.
     * TokenStore 로 이 네임스페이스를 다루지 말 것 — 발급이 "토큰 저장 + 이전 토큰 무효화 + 인덱스 갱신"의
     * 원자적 단일 연산이어야 하는데, TokenStore.issue 로 저장부터 하면 그 사이가 열려 동시 요청 시
     * <b>나중에 발송된 메일의 링크가 죽는다</b>.
     */
    public static final String PASSWORD_RESET = "password-reset";

    private TokenNamespaces() {
    }
}
