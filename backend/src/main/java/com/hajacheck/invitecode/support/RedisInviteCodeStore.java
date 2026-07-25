package com.hajacheck.invitecode.support;

import java.time.Duration;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 InviteCodeStore(#794). test 프로파일은 RedisAutoConfiguration 제외로 StringRedisTemplate 빈이
 * 없으므로 @Profile("!test")로 이 빈 자체를 만들지 않는다(RedisTokenStore와 동일 트레이드오프).
 */
@Component
@Profile("!test")
public class RedisInviteCodeStore implements InviteCodeStore {

    private final StringRedisTemplate redisTemplate;

    public RedisInviteCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean issueIfAbsent(String code, String companyId, Duration ttl) {
        Boolean stored = redisTemplate.opsForValue().setIfAbsent(InviteCodeKeys.key(code), companyId, ttl);
        return Boolean.TRUE.equals(stored);
    }

    @Override
    public Optional<String> peek(String code) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(InviteCodeKeys.key(code)));
    }

    @Override
    public void delete(String code) {
        redisTemplate.delete(InviteCodeKeys.key(code));
    }

    @Override
    public Optional<String> consumeIfPresent(String code) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(InviteCodeKeys.key(code)));
    }
}
