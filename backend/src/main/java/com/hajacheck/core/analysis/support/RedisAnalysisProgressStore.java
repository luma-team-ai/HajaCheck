package com.hajacheck.core.analysis.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link AnalysisProgressStore}의 운영 구현 — RedisTokenStore와 동일하게 {@code @Profile("!test")}로
 * test 프로파일(RedisAutoConfiguration 미적용)에서는 이 빈 대신 {@link InMemoryAnalysisProgressStore}가 뜬다.
 *
 * <p>TTL 6시간 — 분석은 PRD 목표(100장 10분)상 수 분 내 끝나므로 넉넉한 여유. TTL이 지나 캐시가
 * 비어도 GET은 DB(Inspection.status/Defect)로 최선 재구성해 폴백한다(InspectionAnalysisService 참고).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisAnalysisProgressStore implements AnalysisProgressStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;
    // updatedAt(Instant, 코드 리뷰 P2 하트비트) 직렬화에 JavaTimeModule이 필요하다 — 기본
    // ObjectMapper는 java.time 타입을 모른다(Spring이 관리하는 전역 ObjectMapper 빈은 Boot
    // 자동설정으로 이미 등록돼 있지만, 이 클래스는 원래부터 별도 인스턴스를 직접 들고 있었다).
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public void save(AnalysisStatusResponse progress) {
        try {
            String json = objectMapper.writeValueAsString(progress);
            redisTemplate.opsForValue().set(RedisAnalysisKeys.progressKey(progress.inspectionId()), json, TTL);
        } catch (JsonProcessingException | DataAccessException e) {
            // 진행률 캐시 실패로 잡 자체를 중단시키지 않는다(코드 리뷰 P2) — GET이 DB 폴백으로 최선
            // 응답한다. 예전엔 JsonProcessingException(직렬화 오류)만 잡아서, redisTemplate.set이
            // 던지는 RedisConnectionFailureException/RedisSystemException(둘 다 DataAccessException
            // 하위, unchecked) 같은 Redis 연결 장애는 그대로 밖으로 튀어 이 메서드가 이미지별
            // try/catch 바깥(publish())에서 호출되는 InspectionAnalysisWorker.runAsync() 자체를
            // 중단시켰다 — 상태가 ANALYZING에 고착되는 실제 원인이었다.
            log.warn("분석 진행 상태 캐시 저장 실패 — inspectionId={}", progress.inspectionId(), e);
        }
    }

    @Override
    public Optional<AnalysisStatusResponse> find(Long inspectionId) {
        try {
            String json = redisTemplate.opsForValue().get(RedisAnalysisKeys.progressKey(inspectionId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AnalysisStatusResponse.class));
        } catch (JsonProcessingException | DataAccessException e) {
            // 코드 리뷰 P2 — save()와 대칭으로 fail-soft해야 한다. 캐시 "부재"(TTL 만료)에는
            // InspectionAnalysisService.getStatus()가 rebuildFromDb로 폴백하도록 설계돼 있는데,
            // 캐시 "장애"(Redis 연결 불가)에서 예외를 그대로 던지면 그 폴백(orElseGet)이 실행되기도
            // 전에 예외가 전파돼 2초 폴링 GET이 전부 500으로 깨진다. startAnalysis의 ANALYZING
            // 고착 판정(find() 호출)도 같은 이유로 막혀 복구 자체가 불가능해진다.
            log.warn("분석 진행 상태 캐시 조회 실패 — inspectionId={}", inspectionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(Long inspectionId) {
        try {
            redisTemplate.delete(RedisAnalysisKeys.progressKey(inspectionId));
        } catch (DataAccessException e) {
            // save()/find()와 동일한 이유로 fail-soft — TTL(6시간)이 있어 방치돼도 결국 만료된다.
            log.warn("분석 진행 상태 캐시 삭제 실패 — inspectionId={}", inspectionId, e);
        }
    }

    /**
     * PING으로 Redis 연결 자체가 살아있는지 확인한다(코드 리뷰 P2, 사용자 확인 완료) —
     * {@link AnalysisProgressStore#isAvailable} 계약 참고. find()/save()의 fail-soft(예외를
     * Optional.empty()/무시로 흡수)와 별개로, InspectionAnalysisService의 ANALYZING 고착 판정이
     * "캐시 진짜 없음"과 "Redis 장애로 못 읽음"을 구분하는 데 쓴다.
     */
    @Override
    public boolean isAvailable() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            return pong != null;
        } catch (DataAccessException e) {
            log.warn("Redis 가용성 확인 실패", e);
            return false;
        }
    }
}
