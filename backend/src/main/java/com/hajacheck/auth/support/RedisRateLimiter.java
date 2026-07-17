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
        // Redis 장애로 null 이면 열어준다(fail-open): 재설정 요청은 부가 기능이라, Redis 가 흔들릴 때
        // 전 사용자의 비밀번호 찾기를 막는(fail-closed) 쪽이 더 해롭다. 남용 천장은 SMTP 제공자 쿼터가 최종 방어.
        return allowed == null || allowed == 1L;
    }
}
