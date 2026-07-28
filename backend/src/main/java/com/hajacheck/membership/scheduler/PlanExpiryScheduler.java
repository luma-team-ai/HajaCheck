package com.hajacheck.membership.scheduler;

import com.hajacheck.membership.config.PlanExpiryProperties;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.service.PlanExpiryResult;
import com.hajacheck.membership.service.PlanExpiryWriter;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 구독 결제 주기 만료 → FREE 자동 강등 배치(#1145 / HAJA-549). 매일 <b>KST 04:00</b> 실행
 * (점검 알림 배치 06:00과 겹치지 않게).
 *
 * <p><b>왜 필요한가</b>: {@code user_plans.current_period_end} 가 #1104로 실체화됐지만 아무도 그것을
 * 강제하지 않았다 — 엔타이틀먼트 판정({@code QuotaService} 등)은 {@code status = ACTIVE} 만 보므로,
 * 한 번 결제하면 유료 플랜이 무기한 유지된다. 빌링키(정기결제)가 없어 만료 시점 자동 청구가 불가능하므로
 * 실행 가능한 정책은 "만료 시 FREE 강등"이다(2026-07-28 확정).
 *
 * <p><b>대상</b>: {@code status = ACTIVE AND current_period_end IS NOT NULL AND
 * current_period_end < now - gracePeriod}. FREE 는 {@code current_period_end IS NULL}(#1104 규칙)이라
 * 자연 배제된다 — <b>plans 가격으로 다시 필터하지 않는다</b>(두 판정이 갈라지면 #1104의 승계 규칙과
 * 어긋난다). 개인 구독({@code company_id IS NULL})도 포함하며, 좌석·회사 시설물 개념만 건너뛴다.
 *
 * <p><b>⚠️ 이 배치의 가장 큰 위험 — 소급 대량 강등</b>: V27 백필은 {@code current_period_end} 를 실제
 * 결제일이 아니라 {@code started_at + 1개월} 이라는 파생 추정치로 채웠다. 따라서 가입한 지 한 달이 넘은
 * 유료 구독은 이미 전부 "만료" 상태로 DB에 들어 있다. 아무 가드 없이 켜면 첫 실행에서 기존 유료 회사가
 * 일괄 강등되고 좌석까지 정지된다. 그래서 <b>두 개의 안전장치가 핵심</b>이며, 둘 다 테스트로 고정돼 있다:
 * <ol>
 *   <li>{@link PlanExpiryProperties#isEnabled()} — <b>기본 false</b>. 운영자가 프리플라이트(prod 강등
 *       대상 행 수 확인 + 실제 결제 이력 대조)를 마친 뒤에만 켠다. false면 대상 조회조차 하지 않는다.</li>
 *   <li>{@link PlanExpiryProperties#getMaxPerRun()} — 대상 건수가 상한을 넘으면 <b>아무것도 하지 않고</b>
 *       ERROR 로그 후 중단한다. 부분 강등도 남기지 않는다(조회 후 건수 검사 → 초과면 즉시 return).</li>
 * </ol>
 *
 * <p><b>트랜잭션</b>: 이 클래스/메서드에는 {@code @Transactional} 을 붙이지 않는다. 전이는
 * {@link PlanExpiryWriter}(REQUIRES_NEW)가 건별 독립 트랜잭션으로 수행해, 한 건의 실패가 나머지 전체를
 * 막지 않게 한다({@code InspectionDueNotificationScheduler} 와 동일 원칙).
 *
 * <p><b>알림</b>: 강등이 커밋된 뒤 {@link NotificationType#PLAN_EXPIRED} 를 1건 발행한다 — 사용자가
 * 모르는 사이 권한이 내려가면 안 되기 때문이다. 알림 발행 실패는 이미 커밋된 강등을 되돌리지 않고
 * WARN 으로 표면화만 한다. 사전 안내(D-7/D-1)는 이번 범위 밖(후속 이슈).
 *
 * <p><b>시각 판정</b>: {@code SchedulingConfig} 의 KST {@link Clock} 빈을 주입해 쓴다. 비교 자체는
 * {@link Instant} 끼리라 존에 흔들리지 않지만, Clock 주입으로 테스트가 특정 시점을 결정적으로 재현할 수
 * 있게 한다(다른 스케줄러·{@code BuiltYearValidator} 와 동일 원칙).
 *
 * <p>⚠️ <b>단일 인스턴스 실행 전제</b>: 대상 조회는 잠금 없는 스냅샷이라 다중 인스턴스에서는 같은 구독이
 * 겹쳐 조회될 수 있다. 그때도 이중 강등은 나지 않는다 — {@link PlanExpiryWriter} 가 트랜잭션 안에서 대상
 * 조건을 재검증하고, 최종적으로는 부분 UQ({@code uq_user_plans_active_*}: ACTIVE 최대 1건)가 막는다.
 * 다만 상한({@code max-per-run})은 인스턴스별로 따로 세므로, 스케일아웃 시점에는 ShedLock 같은 분산 락
 * 도입을 검토할 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanExpiryScheduler {

    /** 대상이 늘어도 한 번에 다 메모리에 올리지 않도록 페이지 단위로 끊어 순회한다. */
    private static final int PAGE_SIZE = 50;

    private final UserPlanRepository userPlanRepository;
    private final PlanExpiryWriter planExpiryWriter;
    private final NotificationService notificationService;
    private final PlanExpiryProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${hajacheck.plan.expiry.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void expireOverduePlans() {
        if (!properties.isEnabled()) {
            log.info("구독 만료 강등 배치 스킵 — enabled=false(기동 스위치), 대상 조회 0회");
            return;
        }

        Instant threshold = Instant.now(clock).minus(properties.getGracePeriod());
        int maxPerRun = properties.getMaxPerRun();
        long targetCount = userPlanRepository.countExpiryTargets(UserPlanStatus.ACTIVE, threshold);
        if (targetCount == 0) {
            log.info("구독 만료 강등 배치 완료 — 대상 0건 (기준시각 {})", threshold);
            return;
        }
        if (targetCount > maxPerRun) {
            // ⚠️ 부분 강등도 만들지 않는다 — 상한을 넘는 대량 강등은 정상 운영이 아니라 사고 신호다
            // (current_period_end 백필 오염 등). 운영자가 원인을 확인할 때까지 아무것도 하지 않는다.
            log.error("구독 만료 강등 배치 중단 — 대상 {}건이 1회 상한 {}건을 초과했다(강등 0건). "
                            + "hajacheck.plan.expiry.max-per-run 을 올리기 전에 대상 목록이 실제 만료가 "
                            + "맞는지(결제 이력 대조) 먼저 확인할 것. 기준시각 {}",
                    targetCount, maxPerRun, threshold);
            return;
        }

        BatchCounts counts = process(threshold, maxPerRun);
        log.info("구독 만료 강등 배치 완료 — 대상 {}건, 강등 {}건, 스킵 {}건, 실패 {}건, 알림 {}건 (기준시각 {})",
                targetCount, counts.downgraded, counts.skipped, counts.failed, counts.notified, threshold);
    }

    private BatchCounts process(Instant threshold, int maxPerRun) {
        BatchCounts counts = new BatchCounts();
        long lastId = 0L;
        while (true) {
            // keyset 페이징 — 처리한 구독은 EXPIRED 가 되어 결과집합에서 빠지므로 offset 페이징이면
            // 두 번째 페이지부터 대상이 통째로 건너뛰어진다(UserPlanRepository javadoc 참고).
            List<Long> targetIds = userPlanRepository.findExpiryTargetIds(
                    UserPlanStatus.ACTIVE, threshold, lastId, PageRequest.of(0, PAGE_SIZE));
            if (targetIds.isEmpty()) {
                return counts;
            }
            for (Long userPlanId : targetIds) {
                lastId = userPlanId;
                if (counts.downgraded >= maxPerRun) {
                    // 사전 건수 검사 이후에 새로 만료된 구독이 끼어든 경우의 2차 방어 — 상한을 넘겨
                    // 강등하지 않는다(남은 대상은 다음 회차가 처리한다).
                    log.error("구독 만료 강등 중단 — 1회 상한 {}건에 도달했다(강등 {}건). 남은 대상은 "
                                    + "다음 회차로 미룬다.", maxPerRun, counts.downgraded);
                    return counts;
                }
                processOne(userPlanId, threshold, counts);
            }
            if (targetIds.size() < PAGE_SIZE) {
                return counts;
            }
        }
    }

    private void processOne(Long userPlanId, Instant threshold, BatchCounts counts) {
        PlanExpiryResult result;
        try {
            result = planExpiryWriter.expireToFreePlan(userPlanId, threshold);
        } catch (DataIntegrityViolationException e) {
            // 부분 UQ(uq_user_plans_active_company/user: ACTIVE 최대 1건) — 다른 경로가 먼저 활성 구독을
            // 만들었다. 조용히 skip 하고 다음 회차에 자연 재시도한다(InspectionDueNotificationScheduler
            // 의 catch 패턴과 동일).
            counts.skipped++;
            log.info("구독 만료 강등 스킵(활성 구독 경합) — userPlanId={}", userPlanId);
            return;
        } catch (Exception e) {
            // 1건 실패를 격리 — 같은 회차의 나머지 구독 처리는 계속한다.
            counts.failed++;
            log.warn("구독 만료 강등 실패 — userPlanId={} exception={}",
                    userPlanId, e.getClass().getSimpleName());
            return;
        }

        if (!result.downgraded()) {
            counts.skipped++;
            log.info("구독 만료 강등 스킵 — userPlanId={} reason={}", userPlanId, result.skipReason());
            return;
        }
        counts.downgraded++;
        if (publishNotification(userPlanId, result)) {
            counts.notified++;
        }
    }

    /**
     * 강등 알림 1건 발행 — <b>강등 트랜잭션이 커밋된 뒤</b>에 호출된다. 발행 실패가 이미 확정된 강등을
     * 되돌리지 않도록 예외를 삼키고 WARN 으로만 표면화한다(권한은 내려갔는데 알림이 없는 상태는
     * 그 반대보다 낫다 — 반대는 사용자가 멀쩡한 플랜을 잃었다고 오인한다).
     */
    private boolean publishNotification(Long userPlanId, PlanExpiryResult result) {
        Long recipientUserId = result.recipientUserId();
        if (recipientUserId == null) {
            log.warn("구독 만료 강등 알림 수신자 없음 — userPlanId={} companyId={}",
                    userPlanId, result.companyId());
            return false;
        }
        try {
            notificationService.notify(recipientUserId, NotificationType.PLAN_EXPIRED,
                    PlanExpiredNotificationPayload.serialize(result));
            return true;
        } catch (Exception e) {
            log.warn("구독 만료 강등 알림 발행 실패 — userPlanId={} exception={}",
                    userPlanId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** 회차 집계(강등/스킵/실패/알림 건수). */
    private static final class BatchCounts {
        private int downgraded;
        private int skipped;
        private int failed;
        private int notified;
    }
}
