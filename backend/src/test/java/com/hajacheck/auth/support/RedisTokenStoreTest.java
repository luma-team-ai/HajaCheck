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
 * RedisTokenStore 의 <b>저장 키</b> 검증 — "Redis 에 토큰 원문이 저장되지 않는다"를 고정한다.
 *
 * <p>test 프로파일엔 Redis 가 없지만 {@code @Profile("!test")} 는 컨텍스트 로딩에만 걸리므로,
 * mock StringRedisTemplate 으로 직접 생성해 실제 전달되는 키를 ArgumentCaptor 로 잡는다.
 */
class RedisTokenStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisTokenStore tokenStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        tokenStore = new RedisTokenStore(redisTemplate);
    }

    @Test
    void 재설정_토큰은_해시_키로_저장되고_원문은_반환만_된다() {
        String token = tokenStore.issue(TokenNamespaces.PASSWORD_RESET, "42", Duration.ofMinutes(10));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), eq("42"), eq(Duration.ofMinutes(10)));
        String storedKey = keyCaptor.getValue();

        // 핵심: 저장 키에 토큰 원문이 없다(Redis 덤프 유출 → 즉시 계정 탈취 차단).
        assertThat(storedKey)
                .isEqualTo("auth:password-reset:" + TokenKeys.hash(token))
                .doesNotContain(token);
        // 원문 토큰은 호출자(→ 메일)에게만 반환된다.
        assertThat(token).isNotBlank();
    }

    @Test
    void 발급_토큰은_매번_다르다() {
        String first = tokenStore.issue(TokenNamespaces.PASSWORD_RESET, "1", Duration.ofMinutes(10));
        String second = tokenStore.issue(TokenNamespaces.PASSWORD_RESET, "1", Duration.ofMinutes(10));

        // 32바이트 SecureRandom — 2단계에 rate-limit 을 걸지 않는 근거가 이 엔트로피다.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 재설정_토큰_consume_은_해시_키로_조회_삭제한다() {
        String token = "raw-token-value";
        when(valueOps.getAndDelete(any(String.class))).thenReturn("42");

        assertThat(tokenStore.consume(TokenNamespaces.PASSWORD_RESET, token)).contains("42");

        verify(valueOps).getAndDelete("auth:password-reset:" + TokenKeys.hash(token));
    }

    @Test
    void 가입상태_토큰은_원문_키를_유지한다() {
        // 회귀 방지: TTL 30일 in-flight 토큰이 있어 해시로 바꾸면 기존 승인대기 사용자의 상태조회가 깨진다.
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
}
