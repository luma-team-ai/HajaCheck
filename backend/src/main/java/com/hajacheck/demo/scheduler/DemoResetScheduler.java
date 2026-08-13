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
            // 배치 실패를 스케줄러 스레드 밖으로 던지지 않는다(던지면 Spring 스케줄러가 삼켜 더 안 보인다) —
            // 다음 회차가 재시도한다. ⚠️ 이 로그는 "무증상 영구 실패"의 유일한 신호다(#1626 P2-1): FK
            // 누락 등으로 리셋 트랜잭션이 매일 밤 롤백되면 데모 데이터가 계속 오염된 채 쌓인다. ERROR 로
            // 승격하고 스택을 남겨 원인을 특정 가능하게 한다. TODO(후속): 운영 알림 채널(Slack/이메일)
            // 훅을 여기 연결해 로그를 넘어 능동 표면화(현재는 로그 관측에 의존 — NotificationService 는
            // 사용자향이라 이 운영 경보엔 부적합).
            log.error("데모 리셋 배치 실패 — 다음 회차에 재시도한다. 매일 반복되면 리셋이 영구 실패 중이니 "
                    + "즉시 원인(FK/제약 위반 등)을 확인할 것", e);
            return;
        }
        demoResetService.deleteStoredFiles(storageKeys);
    }
}
