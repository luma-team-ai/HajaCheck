package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * RedisTokenStore(가입 상태 토큰)의 저장 키 검증.
 *
 * <p>재설정 토큰은 전용 {@link RedisPasswordResetTokenStore} 로 분리됐으므로, 여기 관심사는
 * <b>기존 가입 토큰 경로가 그대로 유지</b>되는지다(TTL 30일 in-flight 토큰이 있어 키가 바뀌면 깨진다).
 *
 * <p>test 프로파일엔 Redis 가 없지만 {@code @Profile("!test")} 는 컨텍스트 로딩에만 걸리므로,
 * mock StringRedisTemplate 으로 직접 생성해 실제 전달되는 키를 ArgumentCaptor 로 잡는다.
 */
class RedisTokenStoreTest {

    private ValueOperations<String, String> valueOps;
    private RedisTokenStore tokenStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        tokenStore = new RedisTokenStore(redisTemplate);
    }

    @Test
    void 가입상태_토큰은_원문_키로_저장된다() {
        // 회귀 방지: 해시 키로 바꾸면 기존 승인대기 사용자(TTL 30일 토큰)의 상태조회가 전부 깨진다.
        String token = tokenStore.issue(TokenNamespaces.SIGNUP_STATUS, "7", Duration.ofDays(30));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), eq("7"), eq(Duration.ofDays(30)));

        assertThat(keyCaptor.getValue()).isEqualTo("auth:signup-status:" + token);
    }

    @Test
    void 가입상태_토큰_peek_도_원문_키로_조회한다() {
        when(valueOps.get(any(String.class))).thenReturn("7");

        assertThat(tokenStore.peek(TokenNamespaces.SIGNUP_STATUS, "plain-token")).contains("7");

        verify(valueOps).get("auth:signup-status:plain-token");
    }

    @Test
    void consume_은_getAndDelete_로_단일_사용을_보장한다() {
        when(valueOps.getAndDelete(any(String.class))).thenReturn("7");

        assertThat(tokenStore.consume(TokenNamespaces.SIGNUP_STATUS, "plain-token")).contains("7");

        verify(valueOps).getAndDelete("auth:signup-status:plain-token");
    }

    @Test
    void 발급_토큰은_매번_다르다() {
        String first = tokenStore.issue(TokenNamespaces.SIGNUP_STATUS, "1", Duration.ofDays(30));
        String second = tokenStore.issue(TokenNamespaces.SIGNUP_STATUS, "1", Duration.ofDays(30));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 빈_토큰은_Redis를_건드리지_않는다() {
        assertThat(tokenStore.peek(TokenNamespaces.SIGNUP_STATUS, "  ")).isEmpty();
        assertThat(tokenStore.consume(TokenNamespaces.SIGNUP_STATUS, null)).isEmpty();
    }
}
