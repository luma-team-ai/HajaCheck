package com.hajacheck.auth.support;

/**
 * 인증 관련 Redis 키 레이아웃(단일 소스) — SpringBoot_코드_컨벤션.md §8 콜론 규약.
 *
 * <p>{@link RedisTokenStore} 와 {@link RedisPasswordResetTokenIndex} 가 <b>같은 키 공간</b>을 다루므로
 * (인덱스는 토큰 키를 직접 지운다) 레이아웃을 한 곳에 모은다. 양쪽이 따로 문자열을 조립하면
 * 한쪽만 바뀌었을 때 "무효화가 조용히 실패"하는 형태로 깨진다.
 *
 * <p><b>키 충돌 없음</b>: 토큰 키의 마지막 세그먼트는 sha256 hex(64자, [0-9a-f])이고 인덱스 키는
 * {@code user:{userId}} 라 형태가 겹치지 않는다.
 */
public final class RedisAuthKeys {

    private static final String PREFIX = "auth:";
    private static final String USER_INDEX_SEGMENT = "user:";

    private RedisAuthKeys() {
    }

    /** 토큰 키: {@code auth:{namespace}:{저장토큰}}. 저장토큰은 {@link TokenKeys#storageToken} 결과. */
    public static String tokenKey(String namespace, String storageToken) {
        return PREFIX + namespace + ":" + storageToken;
    }

    /** 토큰 키 접두사: {@code auth:{namespace}:} — Lua 가 인덱스 값(토큰해시)에 붙여 토큰 키를 만든다. */
    public static String tokenKeyPrefix(String namespace) {
        return PREFIX + namespace + ":";
    }

    /**
     * 사용자별 현재 비밀번호 재설정 토큰 인덱스 키: {@code auth:password-reset:user:{userId}}.
     * 값 = 현재 유효한 토큰의 sha256. 재발급 시 이전 토큰을 역추적해 지우기 위한 보조 인덱스다
     * (KEYS/SCAN 순회는 운영 Redis 를 블로킹하므로 금지 — 그래서 인덱스를 둔다).
     */
    public static String passwordResetUserIndexKey(long userId) {
        return PREFIX + TokenNamespaces.PASSWORD_RESET + ":" + USER_INDEX_SEGMENT + userId;
    }
}
