package com.hajacheck.auth.support;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 TokenStore. 키 = {@code auth:{namespace}:{token}} (SpringBoot_코드_컨벤션.md §8 콜론 규약, TTL 필수).
 * 토큰은 SecureRandom 32바이트 base64url(불투명, PK 비노출).
 *
 * <p>test 프로파일은 RedisAutoConfiguration 제외로 StringRedisTemplate 빈이 없으므로 @Profile("!test") 로
 * 이 빈 자체를 만들지 않는다(테스트는 in-memory fake 로 대체).
 */
@Component
@Profile("!test")
public class RedisTokenStore implements TokenStore {

    private static final String KEY_PREFIX = "auth:";
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

    private String key(String namespace, String token) {
        return KEY_PREFIX + namespace + ":" + token;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
