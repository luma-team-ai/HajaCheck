package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 재설정 토큰 저장소의 <b>원자성과 저장 키</b>를 고정한다.
 *
 * <p>test 프로파일엔 Redis 가 없지만 {@code @Profile("!test")} 는 컨텍스트 로딩에만 걸리므로,
 * mock StringRedisTemplate 으로 직접 생성해 실제 전달되는 인자를 ArgumentCaptor 로 잡는다.
 * (Lua 스크립트의 실제 동작은 실 Redis 검증 몫 — 여기서는 "왕복이 한 번"과 "키가 해시"를 지킨다.)
 */
class RedisPasswordResetTokenStoreTest {

    private StringRedisTemplate redisTemplate;
    private RedisPasswordResetTokenStore tokenStore;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        tokenStore = new RedisPasswordResetTokenStore(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 발급은_단일_원자연산이다() {
        tokenStore.issueAndRotate(42L, Duration.ofMinutes(10));

        // ⚠️ P2-1 회귀 방지: 토큰 저장과 이전 토큰 무효화를 두 번의 왕복으로 쪼개면 그 사이가 열려,
        // 동시 요청 시 나중에 발송된 메일의 링크가 죽는다. 반드시 스크립트 1회 실행이어야 한다.
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), any(List.class), any(Object[].class));
        // opsForValue 를 통한 별도 SET/DEL 왕복이 있으면 원자성이 깨진 것.
        verify(redisTemplate, times(0)).opsForValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 발급은_토큰_원문이_아니라_해시를_인자로_넘긴다() {
        String token = tokenStore.issueAndRotate(42L, Duration.ofMinutes(10));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

        // 인덱스 키는 userId 기반, 토큰 키는 Lua 가 접두사+해시로 조립한다.
        assertThat(keysCaptor.getValue()).containsExactly("auth:password-reset:user:42");
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo(TokenKeys.hash(token));
        assertThat(args[1]).isEqualTo("42");
        assertThat(args[2]).isEqualTo("600");
        assertThat(args[3]).isEqualTo("auth:password-reset:");
        // 핵심: 어떤 인자에도 토큰 원문이 없다(Redis 덤프 유출 → 즉시 계정 탈취 차단).
        assertThat(args).noneMatch(token::equals);
        assertThat(token).isNotBlank();
    }

    @Test
    void 발급_토큰은_매번_다르다() {
        String first = tokenStore.issueAndRotate(1L, Duration.ofMinutes(10));
        String second = tokenStore.issueAndRotate(1L, Duration.ofMinutes(10));

        // 32바이트 SecureRandom — 2단계에 rate-limit 을 걸지 않는 근거가 이 엔트로피다.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 소비도_단일_원자연산이며_해시로_조회한다() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn("42");

        assertThat(tokenStore.consume("raw-token-value")).contains(42L);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        // 소비 + 인덱스 compare-and-delete 가 한 번에(이전엔 consume 후 별도 인덱스 정리 왕복이 있었다).
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), any(List.class), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[0]).isEqualTo(TokenKeys.hash("raw-token-value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 없는_토큰_소비는_empty다() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(null);

        assertThat(tokenStore.consume("missing")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 저장값이_userId_형식이_아니면_토큰_무효로_취급한다() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn("손상된값");

        assertThat(tokenStore.consume("corrupted")).isEmpty();
    }

    @Test
    void 빈_토큰은_Redis를_건드리지_않는다() {
        assertThat(tokenStore.consume(null)).isEmpty();
        assertThat(tokenStore.consume("  ")).isEmpty();

        verify(redisTemplate, times(0)).execute(any(RedisScript.class), any(List.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void TTL이_1초_미만이어도_최소_1초로_전달된다() {
        // Redis EX 는 양의 정수만 받는다 — 0 을 넘기면 SET 자체가 실패해 토큰이 저장되지 않는다.
        tokenStore.issueAndRotate(1L, Duration.ofMillis(200));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), any(List.class), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[2]).isEqualTo("1");
    }
}
