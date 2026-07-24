package com.hajacheck.platformadmin.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 최근 에러 로그를 Redis capped list(LPUSH+LTRIM)에 저장한다(#728). 최신 항목이 리스트 앞쪽에 오므로
 * 조회는 그대로 LRANGE 0..limit-1 이 최신순이다. RedisRateLimiter 와 동일하게 {@code @Profile("!test")}
 * (test 는 RedisAutoConfiguration 제외로 StringRedisTemplate 부재 — InMemoryErrorLogStore 로 대체).
 */
@Slf4j
@Component
@Profile("!test")
public class RedisErrorLogStore implements ErrorLogStore {

    private static final String KEY = "monitoring:error-logs";
    private static final int MAX_ENTRIES = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisErrorLogStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(ErrorLogItemResponse item) {
        try {
            String json = objectMapper.writeValueAsString(item);
            redisTemplate.opsForList().leftPush(KEY, json);
            redisTemplate.opsForList().trim(KEY, 0, MAX_ENTRIES - 1);
        } catch (Exception e) {
            // 로깅 파이프라인은 부가 기능 — 여기서 실패해도 애플리케이션 로깅 자체(RedisErrorLogAppender
            // 호출부)를 깨서는 안 된다. log.* 호출은 이 appender 로 재귀할 위험이 있어 appender 쪽에서
            // 이미 예외를 흡수하지만, 이 계층에서도 방어적으로 한 번 더 흡수한다(직접 호출자 대비).
            log.debug("에러 로그 Redis 적재 실패", e);
        }
    }

    @Override
    public List<ErrorLogItemResponse> recent(int limit) {
        List<String> raw;
        try {
            raw = redisTemplate.opsForList().range(KEY, 0, Math.max(0, limit - 1));
        } catch (Exception e) {
            // record()와 동일한 fail-silent 정책(PR #766 리뷰 지적) — 여기서 예외를 그대로 던지면
            // Redis 장애 시 GET /api/platform-admin/monitoring 응답 전체(serverHealth·resourceUsage
            // 포함)가 500으로 실패한다. 이 화면은 인프라 장애 상황을 보여주는 게 목적이라, 정작 장애
            // 시점에 대시보드 자체가 뜨지 않는 건 다른 실패 케이스(AI 서버/Actuator → 상태값으로 흡수)
            // 와도 비대칭이다.
            log.debug("에러 로그 Redis 조회 실패", e);
            return List.of();
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ErrorLogItemResponse> items = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                items.add(objectMapper.readValue(json, ErrorLogItemResponse.class));
            } catch (Exception e) {
                log.debug("에러 로그 역직렬화 실패 - 항목 스킵", e);
            }
        }
        return items;
    }
}
