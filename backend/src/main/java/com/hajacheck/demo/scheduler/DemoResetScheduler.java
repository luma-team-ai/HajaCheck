package com.hajacheck.demo.scheduler;

import com.hajacheck.demo.config.DemoResetProperties;
import com.hajacheck.demo.service.DemoResetService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 데모 데이터 일일 리셋 배치(#1626) — 기본 매일 <b>KST 04:10</b>(구독 만료 04:00·점검 알림 06:00 과
 * 분리). 방문자가 만든 데이터를 지우고 시드 상태로 복원한다. 실제 삭제·복원과 안전장치는
 * {@link DemoResetService} 가 담당하고, 여기는 스위치·cron·예외 격리만 맡는다
 * ({@code PlanExpiryScheduler} 와 동일 역할 분담).
 *
 * <p>⚠️ 단일 인스턴스 실행 전제(분산락 미도입 — 프로젝트 선례 없음, PlanExpiryScheduler 와 동일).
 * 다중 인스턴스가 겹쳐 실행돼도 삭제는 companyId 스코프 멱등이고 시드 복원의 중복은 unique 제약
 * (uk_inspections_facility_round)이 한쪽을 롤백시킨다 — 그때는 다음 회차가 자연 복구한다.
 *
 * <p>파일 회수는 <b>트랜잭션 커밋 후</b> best-effort 로 수행한다 — 삭제·재시드가 롤백됐다면
 * {@code resetToSeedState} 가 예외로 빠져나와 파일도 건드리지 않는다(순서 보장).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoResetScheduler {

    private final DemoResetProperties properties;
    private final DemoResetService demoResetService;

    @Scheduled(cron = "${hajacheck.demo.reset.cron:0 10 4 * * *}", zone = "Asia/Seoul")
    public void resetDemoData() {
        if (!properties.isEnabled()) {
            log.debug("데모 리셋 배치 스킵 — enabled=false(기동 스위치)");
            return;
        }
        List<String> storageKeys;
        try {
            storageKeys = demoResetService.resetToSeedState();
        } catch (Exception e) {
            // 배치 실패를 스케줄러 스레드 밖으로 던지지 않는다 — 다음 회차가 재시도한다.
            log.error("데모 리셋 배치 실패 — 다음 회차에 재시도한다", e);
            return;
        }
        demoResetService.deleteStoredFiles(storageKeys);
    }
}
