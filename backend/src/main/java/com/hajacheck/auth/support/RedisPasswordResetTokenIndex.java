package com.hajacheck.auth.support;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 보조 인덱스 구현 — 다단계 연산을 Lua 로 원자화한다(서버에서 단일 실행, 인터리브 불가).
 *
 * <p>MULTI/EXEC 가 아니라 Lua 인 이유: 이전 토큰해시를 <b>읽어서</b> 그 값으로 삭제 대상 키를 만들어야 하는데,
 * MULTI/EXEC 는 큐잉만 하고 중간 결과를 읽을 수 없어 WATCH 재시도 루프가 필요하다. Lua 는 한 번에 끝난다.
 *
 * <p>⚠️ Lua 가 KEYS 에 선언되지 않은 키(이전 토큰 키)를 만지므로 Redis Cluster 에서는 부적합하다.
 * 현 배포는 단일 노드 Redis(compose {@code redis} 서비스)라 문제없다. 클러스터 전환 시 hash tag 로
 * 같은 슬롯에 묶거나 인덱스 설계를 재검토할 것.
 */
@Component
@Profile("!test")
public class RedisPasswordResetTokenIndex implements PasswordResetTokenIndex {

    /**
     * 이전 토큰 무효화 + 인덱스 교체(원자적).
     * KEYS[1]=인덱스 키 · ARGV[1]=새 토큰해시 · ARGV[2]=TTL(초) · ARGV[3]=토큰 키 접두사
     */
    private static final RedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local previous = redis.call('GET', KEYS[1])
            if previous and previous ~= ARGV[1] then
              redis.call('DEL', ARGV[3] .. previous)
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
            """, Long.class);

    /**
     * compare-and-delete — 인덱스가 방금 소비한 토큰을 가리킬 때만 삭제.
     * KEYS[1]=인덱스 키 · ARGV[1]=소비한 토큰해시
     */
    private static final RedisScript<Long> CLEAR_IF_MATCHES = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisPasswordResetTokenIndex(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void rotate(long userId, String newTokenHash, Duration ttl) {
        redisTemplate.execute(
                ROTATE,
                List.of(RedisAuthKeys.passwordResetUserIndexKey(userId)),
                newTokenHash,
                String.valueOf(ttlSeconds(ttl)),
                RedisAuthKeys.tokenKeyPrefix(TokenNamespaces.PASSWORD_RESET));
    }

    @Override
    public void clearIfMatches(long userId, String tokenHash) {
        redisTemplate.execute(
                CLEAR_IF_MATCHES,
                List.of(RedisAuthKeys.passwordResetUserIndexKey(userId)),
                tokenHash);
    }

    /** Redis EX 는 양의 정수만 받는다 — 1초 미만 TTL 설정도 최소 1초로 살린다(0/음수면 SET 자체가 실패). */
    private static long ttlSeconds(Duration ttl) {
        return Math.max(1L, ttl.toSeconds());
    }
}
