package com.hajacheck.core.analysis.support;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link AnalysisProgressStore}의 test 프로파일 전용 구현 — Redis 없이 {@code @SpringBootTest}
 * 컨텍스트를 띄울 수 있게 한다. 프로세스 메모리에만 존재하며 TTL·영속성이 없다(테스트 목적 한정).
 */
@Component
@Profile("test")
public class InMemoryAnalysisProgressStore implements AnalysisProgressStore {

    private final Map<Long, AnalysisStatusResponse> store = new ConcurrentHashMap<>();

    @Override
    public void save(AnalysisStatusResponse progress) {
        store.put(progress.inspectionId(), progress);
    }

    @Override
    public Optional<AnalysisStatusResponse> find(Long inspectionId) {
        return Optional.ofNullable(store.get(inspectionId));
    }

    @Override
    public void delete(Long inspectionId) {
        store.remove(inspectionId);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
