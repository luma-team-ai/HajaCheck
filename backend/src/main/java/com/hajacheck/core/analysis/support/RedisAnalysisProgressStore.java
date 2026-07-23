package com.hajacheck.core.analysis.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void save(AnalysisStatusResponse progress) {
        try {
            String json = objectMapper.writeValueAsString(progress);
            redisTemplate.opsForValue().set(RedisAnalysisKeys.progressKey(progress.inspectionId()), json, TTL);
        } catch (JsonProcessingException e) {
            // 진행률 캐시 실패로 잡 자체를 중단시키지 않는다 — GET이 DB 폴백으로 최선 응답한다.
            log.warn("분석 진행 상태 직렬화 실패 — inspectionId={}", progress.inspectionId(), e);
        }
    }

    @Override
    public Optional<AnalysisStatusResponse> find(Long inspectionId) {
        String json = redisTemplate.opsForValue().get(RedisAnalysisKeys.progressKey(inspectionId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, AnalysisStatusResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("분석 진행 상태 역직렬화 실패 — inspectionId={}", inspectionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(Long inspectionId) {
        redisTemplate.delete(RedisAnalysisKeys.progressKey(inspectionId));
    }
}
