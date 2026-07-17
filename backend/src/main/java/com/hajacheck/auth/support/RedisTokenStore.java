package com.hajacheck.auth.support;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 TokenStore. 키 = {@code auth:{namespace}:{저장토큰}} (SpringBoot_코드_컨벤션.md §8 콜론 규약, TTL 필수).
 * 토큰은 SecureRandom 32바이트 base64url(불투명, PK 비노출) — 비밀번호 재설정 2단계의 실질 방어가 이 엔트로피다.
 *
 * <p>⚠️ <b>저장 토큰 ≠ 발급 토큰</b>: password-reset 네임스페이스는 키에 {@code sha256(token)} 을 쓴다
 * ({@link TokenKeys#storageToken}). 반환·조회 인자는 여전히 토큰 원문이라 {@link TokenStore} 시그니처는
 * 그대로이고, 호출자는 이 파생을 알 필요가 없다. signup-status 는 원문 키 유지(in-flight 토큰 호환).
 *
 * <p>test 프로파일은 RedisAutoConfiguration 제외로 StringRedisTemplate 빈이 없으므로 @Profile("!test") 로
 * 이 빈 자체를 만들지 않는다(테스트는 in-memory fake 로 대체). 단 이 제약은 <b>컨텍스트 로딩</b>에만 걸리므로,
 * 키 조립은 mock StringRedisTemplate 으로 직접 생성해 검증한다(RedisTokenStoreTest).
 */
@Component
@Profile("!test")
public class RedisTokenStore implements TokenStore {

    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public RedisTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String issue(String namespace, String value, Duration ttl) {
        String token = generateToken();
        redisTemplate.opsForValue().set(key(namespace, token), value, ttl);
        return token;
    }

    @Override
    public Optional<String> peek(String namespace, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(namespace, token)));
    }

    @Override
    public Optional<String> consume(String namespace, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        // getAndDelete: 조회와 삭제를 한 번에 — 단일 사용 보장(재설정 토큰 재사용 차단).
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(key(namespace, token)));
    }

    /** 발급 토큰 원문 → 실제 Redis 키. password-reset 은 여기서 해시로 파생된다. */
    private String key(String namespace, String token) {
        return RedisAuthKeys.tokenKey(namespace, TokenKeys.storageToken(namespace, token));
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
