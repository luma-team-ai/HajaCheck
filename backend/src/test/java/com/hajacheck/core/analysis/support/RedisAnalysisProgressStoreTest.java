package com.hajacheck.core.analysis.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * RedisAnalysisProgressStore 단위 테스트(코드 리뷰 P2) — save()가 문서화된 fail-soft 의도대로
 * Redis 연결 장애에서도 예외를 전파하지 않는지 고정한다. RedisTokenStoreTest와 동일하게
 * StringRedisTemplate을 직접 mock으로 생성한다({@code @Profile("!test")}는 컨텍스트 로딩에만 걸림).
 */
class RedisAnalysisProgressStoreTest {

    private ValueOperations<String, String> valueOps;
    private RedisAnalysisProgressStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new RedisAnalysisProgressStore(redisTemplate);
    }

    @Test
    void save_Redis연결장애가나도_예외를전파하지않는다() {
        doThrow(new RedisConnectionFailureException("연결 끊김"))
                .when(valueOps).set(anyString(), anyString(), any());

        assertThatCode(() -> store.save(sampleProgress())).doesNotThrowAnyException();
    }

    private AnalysisStatusResponse sampleProgress() {
        return new AnalysisStatusResponse(
                1L, "aiDetection", 0, 1, 0, List.of(), 0, 0,
                Map.of("A", 0, "B", 0, "C", 0, "D", 0, "E", 0), 0);
    }
}
