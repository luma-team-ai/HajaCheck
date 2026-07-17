package com.hajacheck.auth.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 단방향 해시(순수 함수) — 저장소와 무관하므로 단위 테스트로 고정한다.
 *
 * <p>용도 ① <b>재설정 토큰의 저장 키 파생</b>({@link RedisPasswordResetTokenStore}): 토큰은 그 자체가 계정
 * 탈취 수단이라, 원문을 Redis 키로 쓰면 덤프·스냅샷 유출이 곧 계정 탈취가 된다. {@code sha256(token)} 으로
 * 저장하면 유출돼도 원문을 역산할 수 없다(원문은 메일 수신자만 안다) — 심층방어.
 *
 * <p>용도 ② <b>감사 로그의 이메일 식별자</b>: 로그에 이메일 원문을 남기지 않으면서 같은 대상을 추적하기 위함.
 */
public final class TokenKeys {

    private TokenKeys() {
    }

    /** sha256 hex(소문자 64자). "원문을 키/로그에 남기면 안 되는 값"의 단방향 식별자. */
    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 반드시 제공하는 알고리즘 — 도달 불가.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
