package com.hajacheck.core.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * RedisAnalysisProgressStore 단위 테스트(코드 리뷰 P2) — save/find/delete 전부가 문서화된
 * fail-soft 의도대로 Redis 연결 장애에서도 예외를 전파하지 않는지 고정한다. RedisTokenStoreTest와
 * 동일하게 StringRedisTemplate을 직접 mock으로 생성한다({@code @Profile("!test")}는 컨텍스트
 * 로딩에만 걸림).
 */
class RedisAnalysisProgressStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisAnalysisProgressStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
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

    @Test
    void find_Redis연결장애시_예외를전파하지않고_빈Optional을반환한다() {
        // save()와 대칭 — 캐시 "부재"(TTL 만료)와 캐시 "장애"(연결 불가)를 호출부(getStatus의
        // orElseGet DB 폴백) 입장에서 구분할 필요가 없게 만든다.
        doThrow(new RedisConnectionFailureException("연결 끊김"))
                .when(valueOps).get(anyString());

        Optional<AnalysisStatusResponse> result = store.find(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void delete_Redis연결장애가나도_예외를전파하지않는다() {
        doThrow(new RedisConnectionFailureException("연결 끊김"))
                .when(redisTemplate).delete(anyString());

        assertThatCode(() -> store.delete(1L)).doesNotThrowAnyException();
    }

    @Test
    void save_저장한JSON을find로되읽으면_updatedAt까지동일하다() {
        // 코드 리뷰 P2(하트비트) — updatedAt(Instant) 직렬화에 JavaTimeModule이 필요하다. 이 클래스는
        // Spring이 관리하는 전역 ObjectMapper(Boot 자동설정으로 이미 등록됨)를 안 쓰고 별도 인스턴스를
        // 직접 들고 있어서, 모듈을 깜빡 등록 안 하면 컴파일은 되지만 런타임에 저장/조회가 조용히
        // 깨진다(InvalidDefinitionException) — 실제 라운드트립으로 고정한다.
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        when(valueOps.get(anyString())).thenAnswer(inv -> jsonCaptor.getValue());
        AnalysisStatusResponse original = sampleProgress();

        store.save(original);
        org.mockito.Mockito.verify(valueOps).set(anyString(), jsonCaptor.capture(), any());
        Optional<AnalysisStatusResponse> roundTripped = store.find(1L);

        assertThat(roundTripped).contains(original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void isAvailable_PING성공하면_true를반환한다() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");

        assertThat(store.isAvailable()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void isAvailable_Redis장애면_false를반환하고예외를전파하지않는다() {
        // 코드 리뷰 P2(사용자 확인 완료) — InspectionAnalysisService.stuckReason()이 이 값으로
        // "캐시 진짜 없음"과 "Redis 장애로 못 읽음"을 구분한다. 여기서 예외가 새면 그 판정 로직
        // 자체가 죽는다.
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("연결 끊김"));

        assertThat(store.isAvailable()).isFalse();
    }

    private AnalysisStatusResponse sampleProgress() {
        return new AnalysisStatusResponse(
                1L, "aiDetection", 0, 1, 0, List.of(), 0, 0,
                Map.of("A", 0, "B", 0, "C", 0, "D", 0, "E", 0), 0, java.time.Instant.now());
    }
}
