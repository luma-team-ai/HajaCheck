package com.hajacheck.core.analysis.scheduler;

import com.hajacheck.core.analysis.service.InspectionAnalysisService;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ANALYZING 고착 회차 복구 배치(코드 리뷰 P2 10차).
 *
 * <p>워커가 JVM 재기동·OOM 등으로 죽으면 회차가 {@code ANALYZING}에 고착된다. 요청 경로의 고착 복구
 * ({@link InspectionAnalysisService#startAnalysis})는 사용자가 다시 분석을 눌러야 동작하므로, 사용자가
 * 방치하면 그 회차는 계속 ANALYZING으로 남고 회사별 동시실행 카운트도 갉아먹는다. 이 배치가 주기적으로
 * ANALYZING 회차를 훑어, {@link InspectionAnalysisService#isStuck}(Redis 진행률 하트비트 기준 —
 * inspections 테이블엔 updated_at이 없어 DB 타임스탬프는 쓰지 않는다)로 고착을 판정해 직전 상태
 * (UPLOADING)로 되돌린다. 회사별 동시실행 카운트가 이 배치와 <b>같은 isStuck 정의</b>를 공유하므로,
 * 리퍼가 복원하기 전이라도 고착 회차는 카운트에서 제외돼 회사가 상한에 영구히 걸리지 않는다.
 *
 * <p>⚠️ 단일 인스턴스 실행 전제 — {@link com.hajacheck.core.facility.scheduler.InspectionDueNotificationScheduler}와
 * 동일. 다중 인스턴스로 스케일아웃하면 레플리카마다 각자 돌지만, 복원은 "여전히 ANALYZING일 때만"
 * 되돌리는 멱등 연산({@link com.hajacheck.core.inspection.service.InspectionService#revertStuckAnalyzing})이라
 * 안전하다(중복 복원이 데이터를 손상시키지 않음). 회차별 실패를 격리해 한 건 실패가 배치 전체를 멈추지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckAnalysisReaper {

    // 하트비트 임계(5분)보다 촘촘히 돌아 고착 회차가 오래 잔류하지 않게 한다. 최초 실행은 기동 직후
    // 부하를 피해 1분 뒤부터.
    private static final long REAP_INTERVAL_MS = 120_000L;
    private static final long REAP_INITIAL_DELAY_MS = 60_000L;

    private final InspectionRepository inspectionRepository;
    private final InspectionAnalysisService analysisService;

    @Scheduled(fixedDelay = REAP_INTERVAL_MS, initialDelay = REAP_INITIAL_DELAY_MS)
    public void reapStuckAnalyses() {
        List<Inspection> analyzing = inspectionRepository.findByStatus(InspectionStatus.ANALYZING);
        if (analyzing.isEmpty()) {
            return;
        }

        int reaped = 0;
        for (Inspection inspection : analyzing) {
            try {
                if (analysisService.reapIfStuck(inspection.getId())) {
                    reaped++;
                }
            } catch (Exception e) {
                // 회차 1건 복원 실패를 격리 — 나머지 회차 처리는 계속한다.
                log.warn("ANALYZING 고착 리퍼 개별 복원 실패 — inspectionId={} exception={}",
                        inspection.getId(), e.getClass().getSimpleName());
            }
        }

        log.info("ANALYZING 고착 리퍼 — 대상 {}건 중 {}건 복원", analyzing.size(), reaped);
    }
}
