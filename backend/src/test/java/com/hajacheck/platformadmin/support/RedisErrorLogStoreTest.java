package com.hajacheck.platformadmin.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * PR #766 2차 리뷰 지적(P2) 회귀 테스트 — record()는 Redis 실패를 흡수하는데 recent()는 그렇지 않아
 * Redis 장애 시 GET /api/platform-admin/monitoring 전체가 500으로 실패했다. recent()도 동일하게
 * fail-silent(빈 목록 반환)여야 한다.
 */
class RedisErrorLogStoreTest {

    @SuppressWarnings("unchecked")
    @Test
    void Redis_조회_실패시_예외를_던지지_않고_빈_목록을_반환한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range("monitoring:error-logs", 0, 49)).thenThrow(new RuntimeException("redis down"));

        RedisErrorLogStore store = new RedisErrorLogStore(redisTemplate, new ObjectMapper());

        assertThatCode(() -> store.recent(50)).doesNotThrowAnyException();
        assertThat(store.recent(50)).isEmpty();
    }
}
