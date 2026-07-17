package com.hajacheck.auth.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 토큰 저장 키 파생(순수 함수) — 저장소(Redis)와 무관하므로 단위 테스트로 고정한다.
 *
 * <p><b>왜 해시로 저장하나</b>: 비밀번호 재설정 토큰은 그 자체가 계정 탈취 수단이다. 토큰 원문을 Redis 키로
 * 쓰면 Redis 덤프/스냅샷이 유출되는 순간 그대로 계정 탈취가 된다. 저장 키를 {@code sha256(token)} 으로 두면
 * 유출돼도 원문 토큰을 역산할 수 없다(원문은 메일 수신자만 안다) — 심층방어.
 *
 * <p><b>왜 password-reset 네임스페이스만인가</b>: signup-status 는 TTL 30일 in-flight 토큰이 원문 키로 이미
 * 저장돼 있어, 해시로 바꾸면 배포 즉시 기존 승인대기 사용자의 상태조회가 전부 깨진다. 그래서 네임스페이스로
 * 분기한다({@link TokenStore} 인터페이스 시그니처는 그대로 — 호출자는 이 파생을 몰라도 된다).
 */
public final class TokenKeys {

    private TokenKeys() {
    }

    /** 저장 키를 해시로 파생하는 네임스페이스인지. 현재는 비밀번호 재설정만. */
    public static boolean isHashedNamespace(String namespace) {
        return TokenNamespaces.PASSWORD_RESET.equals(namespace);
    }

    /**
     * 저장소 키에 실제로 쓰일 토큰 표현 — 해시 네임스페이스면 sha256 hex, 아니면 원문 그대로.
     * TokenStore 구현체(Redis·테스트 fake)가 공통으로 이 함수를 거친다.
     */
    public static String storageToken(String namespace, String token) {
        return isHashedNamespace(namespace) ? hash(token) : token;
    }

    /** sha256 hex(소문자 64자). 토큰·이메일 등 "원문을 키/로그에 남기면 안 되는 값"의 단방향 식별자. */
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
