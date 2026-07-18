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
 * <p>⚠️ <b>비밀번호 재설정 토큰은 이 클래스가 다루지 않는다</b> — 전용 {@link PasswordResetTokenStore} 가
 * 소유한다(발급·이전 토큰 무효화·인덱스 갱신이 한 Lua 안에서 원자적이어야 하고, 저장 키도 해시라서).
 * 현재 이 클래스의 사용처는 가입 상태 토큰뿐이며, 그 토큰은 TTL 30일 in-flight 분이 있어 원문 키를 유지한다.
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

    private String key(String namespace, String token) {
        return RedisAuthKeys.tokenKey(namespace, token);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
