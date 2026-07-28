package com.hajacheck.membership.scheduler;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.PlanExpiryProperties;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.service.PlanExpiryResult;
import com.hajacheck.membership.service.PlanExpiryWriter;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 구독 결제 주기 만료 → FREE 자동 강등 배치(#1145 / HAJA-549). 매일 <b>KST 04:00</b> 실행
 * (점검 알림 배치 06:00과 겹치지 않게).
 *
 * <p><b>왜 필요한가</b>: {@code user_plans.current_period_end} 가 #1104로 실체화됐지만 아무도 그것을
 * 강제하지 않았다 — 엔타이틀먼트 판정({@code QuotaService} 등)은 구독 상태만 보므로, 한 번 결제하면
 * 유료 플랜이 무기한 유지된다. 빌링키(정기결제)가 없어 만료 시점 자동 청구가 불가능하므로 실행 가능한
 * 정책은 "만료 시 FREE 강등"이다(2026-07-28 확정).
 *
 * <p><b>대상</b>: {@code status in (ACTIVE, UPGRADE_REQUESTED) AND current_period_end IS NOT NULL AND
 * current_period_end < now - gracePeriod}(+ {@code not-before} 컷오프). FREE 는
 * {@code current_period_end IS NULL}(#1104 규칙)이라 자연 배제된다 — <b>plans 가격으로 다시 필터하지
 * 않는다</b>(두 판정이 갈라지면 #1104의 승계 규칙과 어긋난다). 개인 구독({@code company_id IS NULL})도
 * 포함하며, 좌석·회사 시설물 개념만 건너뛴다.
 *
 * <p><b>⚠️ {@code UPGRADE_REQUESTED} 를 포함하는 이유(리뷰 P1)</b>: 강제 판정은 <b>엔타이틀먼트 판정과
 * 같은 집합</b>이어야 한다({@link PlanExpiryWriter#LIVE_STATUSES}). {@code QuotaService#findLivePlan} 은
 * UPGRADE_REQUESTED 를 살아있는 구독으로 인정해 유료 한도를 계속 적용하는데,
 * {@code UserPlan#requestUpgrade} 는 새 행을 만들지 않고 <b>기존 행의 status 만</b> 바꾸고
 * {@code current_period_end} 는 그대로 둔다. 그래서 ACTIVE 만 대상으로 삼으면 구독자가
 * {@code POST /api/me/plan/upgrade-inquiry} 를 <b>한 번</b> 호출하는 것만으로 만료 강제를 영구히
 * 회피하면서 유료 한도를 계속 쓸 수 있다 — 이 배치가 없애려던 "한 번 결제하면 무기한"이 요청 1건으로
 * 복원된다.
 *
 * <p><b>⚠️ 이 배치의 가장 큰 위험 — 소급 대량 강등</b>: V27 백필은 {@code current_period_end} 를 실제
 * 결제일이 아니라 {@code started_at + 1개월} 이라는 파생 추정치로 채웠다. 따라서 가입한 지 한 달이 넘은
 * 유료 구독은 이미 전부 "만료" 상태로 DB에 들어 있다. 아무 가드 없이 켜면 첫 실행에서 기존 유료 회사가
 * 일괄 강등되고 좌석까지 정지되며, <b>제품 안에는 되돌릴 경로가 없다</b>(무결제 상향이 #988로 차단됨).
 * 그래서 <b>4중 안전장치</b>를 두고 전부 테스트로 고정한다:
 * <ol>
 *   <li>{@link PlanExpiryProperties#isEnabled()} — <b>기본 false</b>. false면 대상 조회조차 하지 않는다.</li>
 *   <li>{@link PlanExpiryProperties#getMode()} — <b>기본 DRY_RUN</b>. 대상 조회·집계·대상 id 로그까지만
 *       하고 단 한 건도 강등하지 않는다. 운영자가 그 목록을 결제 이력과 대조한 뒤 ENFORCE 로 올린다.</li>
 *   <li>{@link PlanExpiryProperties#getNotBefore()} — 설정되면 그보다 이른 만료일은 <b>쿼리 단계에서</b>
 *       빠진다. V27 백필 추정치 구간을 <b>건수와 무관하게</b> 배제하는 컷오프이며,
 *       {@code mode=ENFORCE} 에서는 <b>필수</b>다(미설정이면 기동 실패 — 전 구간을 의도적으로 대상으로
 *       삼으려면 {@code not-before-unbounded=true} 를 명시해야 한다). "무설정 = 무제한" 기본값을 두면
 *       {@code enabled=true} 와 {@code mode=ENFORCE} 를 같은 배포에 함께 넣는 것만으로 DRY_RUN 단계를
 *       건너뛴 채 첫 회차 일괄 강등이 일어난다.</li>
 *   <li>{@link PlanExpiryProperties#getMaxPerRun()} — 대상 건수가 상한을 넘으면 <b>아무것도 하지 않고</b>
 *       ERROR 로그 후 중단한다. 부분 강등도 남기지 않는다(조회 후 건수 검사 → 초과면 즉시 return).
 *       단, 이 상한은 대상이 상한 이하이면 통과하므로 <b>단독으로는 소급 대량 강등을 막지 못한다</b> —
 *       실질 통제는 위의 mode·not-before 다. 또한 이 검사는 <b>DRY_RUN 판정 뒤에</b> 온다: DRY_RUN 은
 *       아무것도 쓰지 않으므로 상한으로 막으면 "목록을 보려면 상한을 올려야 하고, 상한을 올리려면 목록을
 *       봐야 하는" 순환이 생긴다.</li>
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
        Instant notBefore = properties.getNotBefore();
        int maxPerRun = properties.getMaxPerRun();
        long targetCount = userPlanRepository.countExpiryTargets(
                PlanExpiryWriter.LIVE_STATUSES, threshold, notBefore);
        if (targetCount == 0) {
            log.info("구독 만료 강등 배치 완료 — 대상 0건 (기준시각 {} notBefore={})", threshold, notBefore);
            return;
        }
        // ⚠️ DRY_RUN 판정이 1회 상한 검사보다 <b>먼저</b> 와야 한다(리뷰 4). 상한은 "쓰기를 막는" 장치인데
        // DRY_RUN 은 정의상 아무것도 쓰지 않으므로 막을 이유가 없다. 순서를 반대로 두면 승격 절차가
        // 순환한다 — 1단계(DRY_RUN 으로 목록 확인)의 예상 결과가 바로 "대상 > 상한"(백필 추정치로 기존
        // 유료 구독이 이미 전부 만료 상태)인데, 그때 목록 없이 ERROR 만 남고 끝나면 상한을 올릴 근거를
        // 얻을 수 없다. 관측 수단이 가장 필요한 상황에서 정확히 꺼지는 구조가 된다.
        if (!properties.isEnforcing()) {
            reportDryRun(threshold, notBefore, targetCount);
            return;
        }

        if (targetCount > maxPerRun) {
            // ⚠️ 부분 강등도 만들지 않는다 — 상한을 넘는 대량 강등은 정상 운영이 아니라 사고 신호다
            // (current_period_end 백필 오염 등). 운영자가 원인을 확인할 때까지 아무것도 하지 않는다.
            log.error("구독 만료 강등 배치 중단 — 대상 {}건이 1회 상한 {}건을 초과했다(강등 0건). "
                            + "hajacheck.plan.expiry.max-per-run 을 올리기 전에 mode=DRY_RUN 으로 대상 "
                            + "목록을 뽑아 실제 만료가 맞는지(결제 이력 대조) 먼저 확인할 것. "
                            + "기준시각 {} notBefore={}",
                    targetCount, maxPerRun, threshold, notBefore);
            return;
        }

        BatchCounts counts = process(threshold, notBefore, maxPerRun);
        // 실패가 하나라도 있으면 INFO 로 묻지 않는다 — 매일 밤 같은 건이 계속 실패하는 상황(예: owner 가
        // ACTIVE ADMIN 이 아니라 requireSurvivingActiveAdmin 에 영구히 걸리는 회사)을 관측 가능하게
        // 유지해야 한다(리뷰 P2-2).
        String summary = "구독 만료 강등 배치 완료 — 대상 {}건, 강등 {}건, 스킵 {}건, 실패 {}건, 알림 {}건 "
                + "(기준시각 {} notBefore={})";
        if (counts.failed > 0) {
            log.warn(summary, targetCount, counts.downgraded, counts.skipped, counts.failed,
                    counts.notified, threshold, notBefore);
        } else {
            log.info(summary, targetCount, counts.downgraded, counts.skipped, counts.failed,
                    counts.notified, threshold, notBefore);
        }
    }

    /**
     * DRY_RUN 회차 보고 — 강등 없이 <b>대상 id 목록</b>까지 로그로 남긴다. 운영자가 이 목록을 결제 이력과
     * 대조해 "정말 만료된 구독이 맞는지" 확인한 뒤에야 {@code mode=ENFORCE} 로 승격한다.
     * id 만 남기므로 개인정보는 로그에 들어가지 않는다.
     *
     * <p>목록은 {@value #PAGE_SIZE}건까지만 출력하고 나머지는 "이하 생략"으로 표기한다 — 첫 회차에는
     * 대상이 수백 건일 수 있어(백필 추정치) 로그 한 줄이 통제 불가능하게 길어질 수 있다. 승격 판단에는
     * 표본과 총 건수면 충분하고, 전수 확인은 어차피 DB 조회로 한다.
     */
    private void reportDryRun(Instant threshold, Instant notBefore, long targetCount) {
        List<Long> sampleIds = userPlanRepository.findExpiryTargetIds(
                PlanExpiryWriter.LIVE_STATUSES, threshold, notBefore, 0L,
                PageRequest.of(0, PAGE_SIZE));
        String omitted = targetCount > sampleIds.size()
                ? " (이하 " + (targetCount - sampleIds.size()) + "건 생략)"
                : "";
        log.warn("구독 만료 강등 배치 DRY_RUN — 대상 {}건, 강등 0건(모드가 DRY_RUN 이라 아무것도 바꾸지 "
                        + "않았다). 대상 userPlanIds={}{} (기준시각 {} notBefore={}). 실제 반영하려면 "
                        + "이 목록을 결제 이력과 대조한 뒤 hajacheck.plan.expiry.mode=ENFORCE 로 올릴 것.",
                targetCount, sampleIds, omitted, threshold, notBefore);
    }

    private BatchCounts process(Instant threshold, Instant notBefore, int maxPerRun) {
        BatchCounts counts = new BatchCounts();
        long lastId = 0L;
        while (true) {
            // keyset 페이징 — 처리한 구독은 EXPIRED 가 되어 결과집합에서 빠지므로 offset 페이징이면
            // 두 번째 페이지부터 대상이 통째로 건너뛰어진다(UserPlanRepository javadoc 참고).
            List<Long> targetIds = userPlanRepository.findExpiryTargetIds(
                    PlanExpiryWriter.LIVE_STATUSES, threshold, notBefore, lastId,
                    PageRequest.of(0, PAGE_SIZE));
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
        } catch (BusinessException e) {
            handleBusinessFailure(userPlanId, e, counts);
            return;
        } catch (DataIntegrityViolationException e) {
            // ⚠️ writer 는 신규 ACTIVE 발급(saveAndFlush)의 부분 UQ 위반만 골라
            // PLAN_ACTIVE_SUBSCRIPTION_CONFLICT 로 번역해 던진다(위 catch). 그러니 여기까지 올라온
            // 무결성 위반은 사용량 이월·좌석 정지·커밋 시점 등 <b>예상 밖</b>의 것이다 — 조용히 스킵하면
            // 진짜 데이터 결함이 매일 밤 INFO 한 줄로 사라진다(리뷰 P2-1,
            // InspectionDueNotificationScheduler 가 dedupe 인덱스명을 대조해 구분하는 것과 같은 취지).
            counts.failed++;
            // ⚠️ 예외 메시지 원문을 싣지 않는다(리뷰 NEW-3) — PostgreSQL 무결성 위반 메시지는
            // "Detail: Key (col)=(value)" 형태로 <b>위반 컬럼의 실제 값</b>을 담는다. 지금 이 트랜잭션의
            // 쓰기 대상은 id·status 뿐이라 당장 유출될 개인정보는 없지만, 이 경로에 이메일·사업자번호를
            // 건드리는 쓰기가 하나만 추가돼도 즉시 평문 유출이 된다(전역 규칙 위반). 제약명·SQLState 는
            // 원인 특정에 충분하면서 값을 담지 않는다.
            log.warn("구독 만료 강등 실패(예상 밖 무결성 제약 위반) — userPlanId={} {}",
                    userPlanId, describeIntegrityViolation(e));
            // 전체 스택(메시지 포함)은 진단이 필요할 때만 DEBUG 로 본다.
            log.debug("구독 만료 강등 무결성 위반 상세 — userPlanId={}", userPlanId, e);
            return;
        } catch (Exception e) {
            // 1건 실패를 격리 — 같은 회차의 나머지 구독 처리는 계속한다. 스택을 버리지 않는다(리뷰 P3-1):
            // 클래스명만 남기면 영구 실패의 원인을 로그만으로 좁힐 수 없다.
            counts.failed++;
            log.warn("구독 만료 강등 실패 — userPlanId={} exception={} message={}",
                    userPlanId, e.getClass().getSimpleName(), e.getMessage(), e);
            return;
        }

        recordOutcome(userPlanId, result, counts);
    }

    /**
     * 무결성 위반의 <b>값 없는</b> 식별 정보 — 제약명(Hibernate)과 SQLState(JDBC)만 뽑는다.
     * 예외 메시지 원문에는 위반 컬럼 값이 그대로 들어 있어 로그에 남기지 않는다(리뷰 NEW-3).
     */
    private String describeIntegrityViolation(DataIntegrityViolationException e) {
        String constraint = null;
        String sqlState = null;
        for (Throwable cause = e; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (constraint == null && cause instanceof ConstraintViolationException violation) {
                constraint = violation.getConstraintName();
            }
            if (sqlState == null && cause instanceof SQLException sqlException) {
                sqlState = sqlException.getSQLState();
            }
        }
        return "constraint=" + constraint + " sqlState=" + sqlState;
    }

    /** 전이 성공 결과를 집계하고, 실제로 강등됐으면 알림을 1건 발행한다. */
    private void recordOutcome(Long userPlanId, PlanExpiryResult result, BatchCounts counts) {
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
     * 도메인 예외 분류 — 활성 구독 경합만 "다음 회차에 자연 재시도"로 스킵하고, 나머지 ErrorCode 는
     * 실패로 집계하며 <b>코드를 로그에 남긴다</b>(리뷰 P2-2).
     *
     * <p>이 구분이 중요한 이유: FREE 는 {@code max_seats=1} 이라, owner 가 ACTIVE 가 아니거나 ADMIN 이
     * 아닌 회사는 {@code PlanDowngradeService#requireSurvivingActiveAdmin} 에 걸려 <b>매일 밤 같은
     * 예외로 영구 실패</b>한다. ErrorCode 를 버리면 운영자가 로그만 보고는 그 원인을 특정할 수 없다.
     */
    private void handleBusinessFailure(Long userPlanId, BusinessException e, BatchCounts counts) {
        if (e.getErrorCode() == ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT) {
            // 부분 UQ(uq_user_plans_active_company/user: ACTIVE 최대 1건) — 다른 경로가 먼저 활성 구독을
            // 만들었다. 조용히 skip 하고 다음 회차에 자연 재시도한다.
            counts.skipped++;
            log.info("구독 만료 강등 스킵(활성 구독 경합) — userPlanId={}", userPlanId);
            return;
        }
        counts.failed++;
        log.warn("구독 만료 강등 실패 — userPlanId={} errorCode={} message={}",
                userPlanId, e.getErrorCode(), e.getErrorCode().getMessage());
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
