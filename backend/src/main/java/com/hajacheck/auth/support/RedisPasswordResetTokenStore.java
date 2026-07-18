package com.hajacheck.auth.support;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 구현 — 발급/소비를 각각 Lua 한 방으로 처리해 <b>연산 사이의 창을 없앤다</b>.
 *
 * <p>MULTI/EXEC 가 아니라 Lua 인 이유: 이전 토큰해시를 <b>읽어서</b> 그 값으로 삭제 대상 키를 만들어야 하는데
 * MULTI/EXEC 는 큐잉만 하고 중간 결과를 읽을 수 없어 WATCH 재시도 루프가 필요하다. Lua 는 한 번에 끝난다.
 *
 * <p>토큰은 SecureRandom 32바이트 base64url(불투명, PK 비노출) — 2단계에 rate-limit 을 걸지 않는 근거가 이
 * 엔트로피다. 저장 키는 {@code sha256(token)}(Redis 덤프 유출이 곧 계정 탈취가 되지 않도록).
 *
 * <p>⚠️ Lua 가 KEYS 에 선언되지 않은 키(토큰 키·인덱스 키)를 만지므로 Redis Cluster 에서는 부적합하다.
 * 현 배포는 단일 노드 Redis(compose {@code redis} 서비스)라 문제없다. 클러스터 전환 시 hash tag 로 같은
 * 슬롯에 묶거나 설계를 재검토할 것.
 */
@Slf4j
@Component
@Profile("!test")
public class RedisPasswordResetTokenStore implements PasswordResetTokenStore {

    private static final int TOKEN_BYTES = 32;

    /**
     * 발급 + 이전 토큰 무효화 + 인덱스 갱신(원자적).
     * KEYS[1]=인덱스 키 · ARGV[1]=새 토큰해시 · ARGV[2]=userId · ARGV[3]=TTL(초) · ARGV[4]=토큰 키 접두사
     */
    private static final RedisScript<Long> ISSUE_AND_ROTATE = new DefaultRedisScript<>("""
            local previous = redis.call('GET', KEYS[1])
            if previous and previous ~= ARGV[1] then
              redis.call('DEL', ARGV[4] .. previous)
            end
            redis.call('SET', ARGV[4] .. ARGV[1], ARGV[2], 'EX', ARGV[3])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            return 1
            """, Long.class);

    /**
     * 소비(1회용) + 인덱스 compare-and-delete(원자적). 인덱스 키는 값(userId)을 읽어야 알 수 있으므로 Lua 안에서 조립한다.
     * ARGV[1]=토큰해시 · ARGV[2]=토큰 키 접두사 · ARGV[3]=인덱스 키 접두사. 반환=userId 또는 nil
     */
    private static final RedisScript<String> CONSUME = new DefaultRedisScript<>("""
            local tokenKey = ARGV[2] .. ARGV[1]
            local userId = redis.call('GET', tokenKey)
            if not userId then
              return nil
            end
            redis.call('DEL', tokenKey)
            local indexKey = ARGV[3] .. userId
            if redis.call('GET', indexKey) == ARGV[1] then
              redis.call('DEL', indexKey)
            end
            return userId
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public RedisPasswordResetTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String issueAndRotate(long userId, Duration ttl) {
        String token = generateToken();
        redisTemplate.execute(
                ISSUE_AND_ROTATE,
                List.of(RedisAuthKeys.passwordResetUserIndexKey(userId)),
                TokenKeys.hash(token),
                String.valueOf(userId),
                String.valueOf(ttlSeconds(ttl)),
                RedisAuthKeys.tokenKeyPrefix(TokenNamespaces.PASSWORD_RESET));
        return token;
    }

    @Override
    public Optional<Long> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = redisTemplate.execute(
                CONSUME,
                List.of(),
                TokenKeys.hash(token),
                RedisAuthKeys.tokenKeyPrefix(TokenNamespaces.PASSWORD_RESET),
                RedisAuthKeys.passwordResetUserIndexPrefix());
        return parseUserId(userId);
    }

    private static Optional<Long> parseUserId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(value));
        } catch (NumberFormatException e) {
            // 저장값 손상 — 토큰 무효로 취급(원인은 응답에 노출하지 않는다).
            log.warn("비밀번호 재설정 토큰의 저장값이 userId 형식이 아닙니다.");
            return Optional.empty();
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }

    /** Redis EX 는 양의 정수만 받는다 — 1초 미만 TTL 설정도 최소 1초로 살린다(0/음수면 SET 자체가 실패). */
    private static long ttlSeconds(Duration ttl) {
        return Math.max(1L, ttl.toSeconds());
    }
}
