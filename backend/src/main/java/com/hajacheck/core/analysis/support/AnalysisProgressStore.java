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
 * AI 분석 진행 상태 Redis 캐시(dev-05-04) — RedisTokenStore와 동일하게 {@code @Profile("!test")}로
 * 컨텍스트 로딩을 test 프로파일에서 제외한다(RedisAutoConfiguration 미적용). 이 스토어에 의존하는
 * {@link com.hajacheck.core.analysis.service.InspectionAnalysisService}/
 * {@link com.hajacheck.core.analysis.controller.InspectionAnalysisController}도 같은 이유로
 * 동일 프로파일 제약을 건다 — 이번 범위는 이 세 빈에 대한 자동화 테스트를 포함하지 않는다(수동 검증만).
 *
 * <p>TTL 6시간 — 분석은 PRD 목표(100장 10분)상 수 분 내 끝나므로 넉넉한 여유. TTL이 지나 캐시가
 * 비어도 GET은 DB(Inspection.status/Defect)로 최선 재구성해 폴백한다(InspectionAnalysisService 참고).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AnalysisProgressStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void save(AnalysisStatusResponse progress) {
        try {
            String json = objectMapper.writeValueAsString(progress);
            redisTemplate.opsForValue().set(RedisAnalysisKeys.progressKey(progress.inspectionId()), json, TTL);
        } catch (JsonProcessingException e) {
            // 진행률 캐시 실패로 잡 자체를 중단시키지 않는다 — GET이 DB 폴백으로 최선 응답한다.
            log.warn("분석 진행 상태 직렬화 실패 — inspectionId={}", progress.inspectionId(), e);
        }
    }

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
}
