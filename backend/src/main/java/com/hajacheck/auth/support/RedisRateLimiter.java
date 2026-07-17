package com.hajacheck.auth.support;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 고정 창 카운터 rate-limiter.
 *
 * <p>INCR 과 만료 설정을 Lua 로 묶는 이유: {@code INCR} 후 별도 {@code EXPIRE} 를 보내면 그 사이에 죽었을 때
 * TTL 없는 카운터가 영구 잔존해 <b>그 키가 영원히 429</b> 가 된다(첫 요청자가 창 없이 갇힘).
 *
 * <p>창은 첫 요청 시각부터 시작한다(sliding window 아님). 창 경계에서 최대 2배 버스트가 가능하지만,
 * 이 축의 목적(메일 폭탄 방어·발신 평판 보호)에는 충분하고 구현이 단순하다.
 */
@Component
@Profile("!test")
public class RedisRateLimiter implements RateLimiter {

    /** KEYS[1]=카운터 키 · ARGV[1]=창 길이(ms) · ARGV[2]=한도. 반환 1=허용, 0=초과 */
    private static final RedisScript<Long> TRY_ACQUIRE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if count > tonumber(ARGV[2]) then
              return 0
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        Long allowed = redisTemplate.execute(
                TRY_ACQUIRE,
                List.of(key),
                String.valueOf(Math.max(1L, window.toMillis())),
                String.valueOf(limit));
        // Redis 장애 시 execute() 는 null 이 아니라 RedisConnectionFailureException 을 던져 500 이 된다.
        // 그대로 수용한다: 이 플로우 전체가 Redis 에 의존하므로(토큰 발급·보조 인덱스·세션 저장소) Redis 가
        // 죽으면 재설정 자체가 성립하지 않는다 — rate-limit 만 fail-open 시켜도 의미가 없다.
        // ⚠️ fail-open 을 명시적으로 구현하지 말 것: 훗날 발송 경로가 Redis 비의존이 되면 Redis 블립 동안
        // 무제한 메일 폭탄이 열린다. 스크립트가 항상 0/1 을 반환해 null 은 도달 불가하지만 방어적으로 차단한다.
        return Long.valueOf(1L).equals(allowed);
    }
}
