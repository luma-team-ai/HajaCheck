package com.hajacheck.core.analysis.support;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import java.util.Optional;

/**
 * AI 분석 진행 상태 캐시(dev-05-04) — 구현을 인터페이스 뒤로 감춰 Redis 의존 없이(test 프로파일)
 * {@link com.hajacheck.core.analysis.service.InspectionAnalysisService}/
 * {@link com.hajacheck.core.analysis.service.InspectionAnalysisWorker}를 단위 테스트할 수 있게
 * 한다(코드 리뷰 P2 픽스 — 이전에는 세 빈 모두 {@code @Profile("!test")}로 컨텍스트에서 배제해
 * 자동화 테스트가 전혀 없었다). 운영은 {@link RedisAnalysisProgressStore}, 테스트는
 * {@link InMemoryAnalysisProgressStore}가 각각 프로파일별로 유일하게 활성화된다.
 */
public interface AnalysisProgressStore {

    void save(AnalysisStatusResponse progress);

    Optional<AnalysisStatusResponse> find(Long inspectionId);

    /** 큐잉 실패로 롤백하거나(TaskRejectedException) 전체 실패로 상태를 되돌릴 때 잔여 캐시를 지운다. */
    void delete(Long inspectionId);
}
