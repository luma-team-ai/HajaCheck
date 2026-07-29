package com.hajacheck.membership.scheduler;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.ScheduledPlanChangeProperties;
import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.service.PaymentGraceService;
import com.hajacheck.membership.service.PlanExpiryResult;
import com.hajacheck.membership.service.PlanExpiryWriter;
import com.hajacheck.membership.service.ScheduledPlanChangeFailure;
import com.hajacheck.membership.service.ScheduledPlanChangeResult;
import com.hajacheck.membership.service.ScheduledPlanChangeWriter;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약된 플랜 하향 적용 배치(#1105 / HAJA-526). <b>매시 정각(KST)</b> 실행 — 적용 시각
 * ({@code effective_at} = 예약 시점의 {@code user_plans.current_period_end})이 지난 예약을 찾아 하향을
 * 반영한다.
 *
 * <p><b>2단계 구성(#1177 유료→유료 하향 C안)</b>: 한 회차가 같은 기준시각으로 두 가지를 처리한다.
 * <ol>
 *   <li><b>예약 적용</b>(원래 동작) — 적용 시각이 지난 하향 예약을 반영한다. 대상이 <b>유료</b>면 결제
 *       없이 그 요금제를 발급하되 <b>미결제 유예</b>로 들어간다(한도는 FREE).</li>
 *   <li><b>유예 만료 강등</b> — 유예 마감이 지났는데 결제되지 않은 구독을 FREE 로 강등한다
 *       ({@link #expirePaymentGracePeriods}). 만료 강등 배치(#1145)에 얹지 않은 이유는 그 배치가
 *       {@code enabled=false} 로 잠겨 있어 이 기능까지 함께 잠기기 때문이다.</li>
 * </ol>
 * 두 단계 모두 비상 스위치({@code enabled=false})와 1회 상한({@code max-per-run})의 통제를 받는다.
 *
 * <p><b>왜 필요한가</b>: 관리자 콘솔의 요금제 변경은 즉시 전이라, 하향을 신청하는 순간 초과 좌석이 그
 * 자리에서 정지된다. 이미 낸 요금 기간이 남아 있어도 권한이 바로 내려가므로 통상의 SaaS 관례("다음 결제
 * 주기부터 적용")와 어긋난다. 예약({@code scheduled_plan_changes})은 그 신청을 기록만 해 두고, 이 배치가
 * 잔여 기간이 끝나는 시점에 <b>무결제 전이</b>로 반영한다.
 *
 * <p><b>왜 매시인가</b>: 적용 시각은 결제 주기 종료 시각이라 하루 중 아무 때나 걸린다. 일 1회 배치면
 * 최악의 경우 하루 가까이 상위 요금제가 무료로 연장된다. 매시 정각이면 지연이 1시간 이내로 제한되고,
 * 대상은 사람이 만든 예약뿐이라 조회 비용도 부분 인덱스({@code idx_scheduled_plan_changes_due})로 무시할
 * 수 있다.
 *
 * <p><b>트랜잭션</b>: 이 클래스/메서드에는 {@code @Transactional} 을 붙이지 않는다. 전이는
 * {@link ScheduledPlanChangeWriter}(REQUIRES_NEW)가 건별 독립 트랜잭션으로 수행해, 한 건의 실패가 나머지
 * 전체를 막지 않게 한다({@code PlanExpiryScheduler}·{@code InspectionDueNotificationScheduler} 와 동일 원칙).
 *
 * <p><b>실패 분류(중요)</b> — 재시도로 풀리는가로 나눈다:
 * <ul>
 *   <li><b>도메인 위반</b>({@link BusinessException}, 단 {@link ErrorCode#PLAN_ACTIVE_SUBSCRIPTION_CONFLICT}
 *       제외) → 예약을 <b>FAILED 로 종료</b>한다. 좌석 한도 정책 변경·ACTIVE ADMIN 부재 같은 원인은
 *       배치가 스스로 고칠 수 없어 매시 재시도해도 같은 예외만 반복된다. 종료시켜야 로그가 사고 신호로
 *       남고(요약 WARN 1회), 회사는 상위 요금제를 유지하므로 <b>아무도 잘못 정지되지 않는다</b>.</li>
 *   <li><b>경합</b>(활성 구독 UQ 충돌·행 잠금 대기 초과) → 상태를 <b>PENDING 으로 남기고</b> 다음 회차에
 *       자연 재시도한다. 결함이 아니라 순간적인 경합이라 실패로 세지도 않는다(회차 요약 WARN 이 일상적인
 *       경합으로 켜지면 진짜 실패 신호가 희석된다 — {@code PlanExpiryScheduler} 리뷰 NEW-D 와 같은 취지).</li>
 *   <li><b>예상 밖 예외</b>(인프라 장애 등) → 실패로 세되 상태는 <b>PENDING 으로 남긴다</b>. 일시 장애로
 *       사용자가 신청한 하향을 영구히 잃게 만들지 않기 위해서다. 그 대신 회차 요약이 WARN 으로 올라가
 *       "매시 같은 건이 실패한다"가 관측 가능하다.</li>
 * </ul>
 *
 * <p><b>알림</b>: 적용이 커밋된 뒤 {@link NotificationType#PLAN_DOWNGRADED} 를 1건 발행한다 — 신청자가
 * 화면을 보고 있지 않는 사이 구성원 계정이 정지되므로 필수다. <b>FAILED 로 종료될 때도</b>
 * {@link NotificationType#PLAN_DOWNGRADE_FAILED} 를 1건 발행한다(리뷰 P2-5): FAILED 는 종료 상태라
 * 재시도가 없고 조회({@code AdminPlanResponse.scheduledChange})는 PENDING 만 노출하므로, 알리지 않으면
 * 신청자에게는 "예약을 걸었는데 어느 날 조용히 사라지고 요금제는 그대로"가 된다. 어느 쪽이든 알림 발행
 * 실패는 이미 커밋된 상태 변경을 되돌리지 않고 WARN 으로 표면화만 한다.
 *
 * <p><b>시각 판정</b>: {@code SchedulingConfig} 의 KST {@link Clock} 빈을 주입해 쓴다. 비교 자체는
 * {@link Instant} 끼리라 존에 흔들리지 않지만, Clock 주입으로 테스트가 특정 시점을 결정적으로 재현할 수
 * 있게 한다(다른 스케줄러와 동일 원칙).
 *
 * <p>⚠️ <b>단일 인스턴스 실행 전제</b>: 대상 조회는 잠금 없는 스냅샷이라 다중 인스턴스에서는 같은 예약이
 * 겹쳐 조회될 수 있다. 그때도 이중 적용은 나지 않는다 — {@link ScheduledPlanChangeWriter} 가 예약 행을
 * 잠그고 {@code UPDATE ... WHERE status = 'PENDING'} 로 점유하며, 최종적으로는 부분 UQ
 * ({@code uq_user_plans_active_*}: ACTIVE 최대 1건)가 막는다. 다만 상한({@code max-per-run})은 인스턴스별로
 * 따로 세므로, 스케일아웃 시점에는 ShedLock 같은 분산 락 도입을 검토할 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPlanChangeScheduler {

    /** 대상이 늘어도 한 번에 다 메모리에 올리지 않도록 페이지 단위로 끊어 순회한다. */
    private static final int PAGE_SIZE = 50;

    private final ScheduledPlanChangeRepository scheduledPlanChangeRepository;
    private final ScheduledPlanChangeWriter scheduledPlanChangeWriter;
    // 2단계(#1177 미결제 유예 강등) — 후보 조회·유예 확정 판정·강등 실행. 강등 자체는 만료 강등 경로를
    // 그대로 재사용한다(expirePaymentGracePeriods javadoc).
    private final UserPlanRepository userPlanRepository;
    private final PaymentGraceService paymentGraceService;
    private final PlanExpiryWriter planExpiryWriter;
    private final NotificationService notificationService;
    private final ScheduledPlanChangeProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${hajacheck.plan.scheduled-change.cron:0 0 * * * *}", zone = "Asia/Seoul")
    public void applyDueScheduledChanges() {
        if (!properties.isEnabled()) {
            log.info("예약 하향 적용 배치 스킵 — enabled=false(비상 스위치), 대상 조회 0회");
            return;
        }

        Instant now = Instant.now(clock);
        applyDueReservations(now);
        // 2단계 — 미결제 유예가 만료된 구독의 FREE 강등(#1177). 1단계와 <b>같은 기준시각</b>을 쓴다.
        // 순서가 1단계 뒤인 이유: 같은 회차에서 방금 유예로 진입한 구독(유예 종료가 미래)이 2단계 조회에
        // 걸리지 않으므로 "발급하자마자 강등"이 구조적으로 불가능하다(payment-grace-days >= 1 과 함께).
        expirePaymentGracePeriods(now);
    }

    /** 1단계 — 적용 시각이 지난 하향 예약을 반영한다(#1105 원래 동작). */
    private void applyDueReservations(Instant now) {
        int maxPerRun = properties.getMaxPerRun();
        long targetCount = scheduledPlanChangeRepository.countDue(now);
        if (targetCount == 0) {
            log.debug("예약 하향 적용 배치 완료 — 대상 0건 (기준시각 {})", now);
            return;
        }
        if (targetCount > maxPerRun) {
            // ⚠️ 부분 적용도 만들지 않는다 — 상한을 넘는 대량 적용은 정상 운영이 아니라 사고 신호다
            // (예약 생성 경로 오류·시각 판정 오류 등). 운영자가 원인을 확인할 때까지 아무것도 하지 않는다.
            log.error("예약 하향 적용 배치 중단 — 대상 {}건이 1회 상한 {}건을 초과했다(적용 0건). "
                            + "hajacheck.plan.scheduled-change.max-per-run 을 올리기 전에 "
                            + "scheduled_plan_changes 의 PENDING 행이 실제 신청이 맞는지 먼저 확인할 것. "
                            + "기준시각 {}",
                    targetCount, maxPerRun, now);
            return;
        }

        BatchCounts counts = process(now, maxPerRun);
        String summary = "예약 하향 적용 배치 완료 — 대상 {}건, 적용 {}건, 무효취소 {}건, 스킵 {}건, "
                + "실패 {}건, 알림 {}건 (기준시각 {})";
        if (counts.failed > 0) {
            // 실패가 하나라도 있으면 INFO 로 묻지 않는다 — 매시 같은 건이 계속 실패하는 상황을 관측
            // 가능하게 유지해야 한다(PlanExpiryScheduler 리뷰 P2-2 와 같은 취지).
            log.warn(summary, targetCount, counts.applied, counts.canceled, counts.skipped,
                    counts.failed, counts.notified, now);
        } else {
            log.info(summary, targetCount, counts.applied, counts.canceled, counts.skipped,
                    counts.failed, counts.notified, now);
        }
    }

    /**
     * 2단계 — <b>미결제 유예가 만료된 구독을 FREE 로 강등</b>한다(#1177 C안).
     *
     * <p><b>왜 이 배치인가</b>: 만료 강등 배치({@code PlanExpiryScheduler}, #1145)는 V27 백필 추정 만료일
     * 때문에 {@code enabled=false} 로 잠겨 있다. 거기에 얹으면 이 기능도 함께 잠긴다. 반면 이 배치는 매시
     * 활성이고 유예 자체가 이 배치가 만든 상태라, 발급과 만료를 같은 배치가 책임지는 편이 일관된다.
     *
     * <p><b>강등 로직은 새로 짜지 않는다</b> — 결과가 "유료 구독을 FREE 로 내리고 초과 좌석을 정지"로
     * 만료 강등과 <b>완전히 같은 사건</b>이므로 {@link PlanExpiryWriter#expireToFreePlan} 을 그대로
     * 호출한다(그 writer 는 {@code PlanExpiryProperties.enabled} 를 보지 않는다 — 스위치는 스케줄러 쪽에만
     * 있다). 유예 구독은 {@code current_period_end} 가 유예 마감이라 그 writer 의 재검증 조건
     * ({@code currentPeriodEnd < threshold})을 그대로 만족한다. 알림도 같은 이유로
     * {@link NotificationType#PLAN_EXPIRED} 를 재사용한다(사용자에게는 "결제가 되지 않아 FREE 로
     * 전환됐다"는 동일한 사건이고, 새 라벨은 PG enum 변경 = 스키마 변경을 부른다).
     *
     * <p><b>후보 → 확정 2단 판정</b>: 조회({@code findPaymentGraceExpiredIds})는 "예약 실행이 발급했고
     * 주기가 끝난 유료 구독"까지만 좁히고, <b>실제 강등 여부는 {@link PaymentGraceService#isInGracePeriod}
     * 이 최종 확인</b>한다. 결제가 이미 끝난 구독을 강등하지 않기 위해서다 — 정상 경로에서는 결제가 새 행을
     * 발급하며 유예 행을 EXPIRED 로 내리므로 조회에서 이미 빠지지만, 그 사이(조회~처리)의 경합과
     * "PAID 인데 전이가 끝나지 않은" 대사 대상(#1010)까지 여기서 막는다.
     */
    private void expirePaymentGracePeriods(Instant now) {
        int maxPerRun = properties.getMaxPerRun();
        long targetCount = userPlanRepository.countPaymentGraceExpired(PlanExpiryWriter.LIVE_STATUSES, now);
        if (targetCount == 0) {
            log.debug("미결제 유예 강등 배치 완료 — 대상 0건 (기준시각 {})", now);
            return;
        }
        if (targetCount > maxPerRun) {
            // 1단계와 같은 원칙 — 상한을 넘는 대량 강등은 정상 운영이 아니라 사고 신호다(부분 적용 금지).
            log.error("미결제 유예 강등 배치 중단 — 대상 {}건이 1회 상한 {}건을 초과했다(강등 0건). "
                            + "user_plans 의 유예 후보가 실제 예약 실행 결과가 맞는지 먼저 확인할 것. 기준시각 {}",
                    targetCount, maxPerRun, now);
            return;
        }

        BatchCounts counts = processGraceExpiry(now, maxPerRun);
        String summary = "미결제 유예 강등 배치 완료 — 대상 {}건, 강등 {}건, 스킵 {}건, 실패 {}건, "
                + "알림 {}건 (기준시각 {})";
        if (counts.failed > 0) {
            log.warn(summary, targetCount, counts.applied, counts.skipped, counts.failed,
                    counts.notified, now);
        } else {
            log.info(summary, targetCount, counts.applied, counts.skipped, counts.failed,
                    counts.notified, now);
        }
    }

    private BatchCounts processGraceExpiry(Instant now, int maxPerRun) {
        BatchCounts counts = new BatchCounts();
        long lastId = 0L;
        while (true) {
            // keyset 페이징 — 강등된 구독은 EXPIRED 가 되어 결과집합에서 빠지므로 offset 페이징이면 두
            // 번째 페이지부터 대상이 통째로 건너뛰어진다(1단계와 같은 이유).
            List<Long> targetIds = userPlanRepository.findPaymentGraceExpiredIds(
                    PlanExpiryWriter.LIVE_STATUSES, now, lastId, PageRequest.of(0, PAGE_SIZE));
            if (targetIds.isEmpty()) {
                return counts;
            }
            for (Long userPlanId : targetIds) {
                lastId = userPlanId;
                if (counts.applied >= maxPerRun) {
                    log.error("미결제 유예 강등 중단 — 1회 상한 {}건에 도달했다(강등 {}건). 남은 대상은 "
                            + "다음 회차로 미룬다.", maxPerRun, counts.applied);
                    return counts;
                }
                processGraceExpiryOne(userPlanId, now, counts);
            }
            if (targetIds.size() < PAGE_SIZE) {
                return counts;
            }
        }
    }

    private void processGraceExpiryOne(Long userPlanId, Instant now, BatchCounts counts) {
        PlanExpiryResult result;
        try {
            if (!paymentGraceService.isInGracePeriodById(userPlanId)) {
                // 결제가 끝났거나(정산 완료) 유예 후보가 아니게 됐다 — 강등하지 않는다(위 javadoc).
                counts.skipped++;
                log.info("미결제 유예 강등 스킵(이미 정산됨 또는 유예 아님) — userPlanId={}", userPlanId);
                return;
            }
            result = planExpiryWriter.expireToFreePlan(userPlanId, now);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT) {
                // 다른 경로가 먼저 활성 구독을 만들었다 — 경합이지 결함이 아니므로 다음 회차에 자연 재시도.
                counts.skipped++;
                log.info("미결제 유예 강등 스킵(활성 구독 경합) — userPlanId={}", userPlanId);
                return;
            }
            counts.failed++;
            log.warn("미결제 유예 강등 실패 — userPlanId={} errorCode={}", userPlanId, e.getErrorCode());
            return;
        } catch (PessimisticLockingFailureException e) {
            counts.skipped++;
            log.info("미결제 유예 강등 스킵(행 잠금 대기 초과) — userPlanId={}", userPlanId);
            return;
        } catch (DataIntegrityViolationException e) {
            // 값 없는 식별 정보만 남긴다(1단계와 같은 규칙 — PG 위반 메시지는 컬럼 값을 담는다).
            counts.failed++;
            log.warn("미결제 유예 강등 실패(예상 밖 무결성 제약 위반) — userPlanId={} {}",
                    userPlanId, describeIntegrityViolation(e));
            log.debug("미결제 유예 강등 무결성 위반 상세 — userPlanId={}", userPlanId, e);
            return;
        } catch (Exception e) {
            counts.failed++;
            log.warn("미결제 유예 강등 실패 — userPlanId={} exception={} message={}",
                    userPlanId, e.getClass().getSimpleName(), e.getMessage(), e);
            return;
        }

        if (!result.downgraded()) {
            counts.skipped++;
            log.info("미결제 유예 강등 스킵 — userPlanId={} reason={}", userPlanId, result.skipReason());
            return;
        }
        counts.applied++;
        if (publishGraceExpiryNotification(userPlanId, result)) {
            counts.notified++;
        }
    }

    /**
     * 유예 만료 강등 알림 1건({@link NotificationType#PLAN_EXPIRED}) — <b>강등이 커밋된 뒤</b> 발행한다.
     * 발행 실패가 이미 확정된 강등을 되돌리지 않도록 예외를 삼키고 WARN 으로만 표면화한다(1단계와 동일).
     */
    private boolean publishGraceExpiryNotification(Long userPlanId, PlanExpiryResult result) {
        if (result.recipientUserId() == null) {
            log.warn("미결제 유예 강등 알림 수신자 없음 — userPlanId={}", userPlanId);
            return false;
        }
        try {
            notificationService.notify(result.recipientUserId(), NotificationType.PLAN_EXPIRED,
                    PlanExpiredNotificationPayload.serialize(result));
            return true;
        } catch (Exception e) {
            log.warn("미결제 유예 강등 알림 발행 실패 — userPlanId={} exception={}",
                    userPlanId, e.getClass().getSimpleName());
            return false;
        }
    }

    private BatchCounts process(Instant now, int maxPerRun) {
        BatchCounts counts = new BatchCounts();
        long lastId = 0L;
        while (true) {
            // keyset 페이징 — 처리한 예약은 PENDING 이 아니게 되어 결과집합에서 빠지므로 offset 페이징이면
            // 두 번째 페이지부터 대상이 통째로 건너뛰어진다(ScheduledPlanChangeRepository javadoc 참고).
            // ⚠️ 재시도로 남긴 PENDING(경합·인프라 장애)은 결과집합에 그대로 남는다 — lastId 로 커서를
            // 전진시키므로 같은 회차에서 무한 재조회되지 않고 다음 회차가 다시 집는다.
            List<Long> targetIds = scheduledPlanChangeRepository.findDueIds(
                    now, lastId, PageRequest.of(0, PAGE_SIZE));
            if (targetIds.isEmpty()) {
                return counts;
            }
            for (Long scheduledChangeId : targetIds) {
                lastId = scheduledChangeId;
                if (counts.applied >= maxPerRun) {
                    // 사전 건수 검사 이후에 새로 만기된 예약이 끼어든 경우의 2차 방어 — 상한을 넘겨
                    // 적용하지 않는다(남은 대상은 다음 회차가 처리한다).
                    log.error("예약 하향 적용 중단 — 1회 상한 {}건에 도달했다(적용 {}건). 남은 대상은 "
                            + "다음 회차로 미룬다.", maxPerRun, counts.applied);
                    return counts;
                }
                processOne(scheduledChangeId, now, counts);
            }
            if (targetIds.size() < PAGE_SIZE) {
                return counts;
            }
        }
    }

    private void processOne(Long scheduledChangeId, Instant now, BatchCounts counts) {
        ScheduledPlanChangeResult result;
        try {
            result = scheduledPlanChangeWriter.applyDueChange(scheduledChangeId, now);
        } catch (BusinessException e) {
            handleBusinessFailure(scheduledChangeId, e, counts);
            return;
        } catch (PessimisticLockingFailureException e) {
            // 행 잠금 대기 상한 초과(PG 55P03) — 다른 트랜잭션이 같은 구독/예약 행을 오래 잡고 있었다는
            // 뜻이라 결함이 아니라 경합이다. 상태를 PENDING 으로 남겨 다음 회차에 자연 재시도한다.
            counts.skipped++;
            log.info("예약 하향 적용 스킵(행 잠금 대기 초과) — scheduledChangeId={}", scheduledChangeId);
            return;
        } catch (DataIntegrityViolationException e) {
            // writer 는 신규 ACTIVE 발급의 부분 UQ 위반만 골라 PLAN_ACTIVE_SUBSCRIPTION_CONFLICT 로
            // 번역해 던진다(위 catch). 여기까지 올라온 무결성 위반은 예상 밖이므로 실패로 세되, 일시적일
            // 수 있어 상태는 PENDING 으로 남긴다.
            //
            // ⚠️ 예외 메시지 원문을 싣지 않는다 — PostgreSQL 무결성 위반 메시지는 "Detail: Key
            // (col)=(value)" 형태로 <b>위반 컬럼의 실제 값</b>을 담는다(전역 규칙: 개인정보 평문 로깅 금지).
            // 제약명·SQLState 는 원인 특정에 충분하면서 값을 담지 않는다.
            counts.failed++;
            log.warn("예약 하향 적용 실패(예상 밖 무결성 제약 위반) — scheduledChangeId={} {}",
                    scheduledChangeId, describeIntegrityViolation(e));
            log.debug("예약 하향 적용 무결성 위반 상세 — scheduledChangeId={}", scheduledChangeId, e);
            return;
        } catch (Exception e) {
            // 1건 실패를 격리 — 같은 회차의 나머지 예약 처리는 계속한다. 스택을 버리지 않는다(클래스명만
            // 남기면 영구 실패의 원인을 로그만으로 좁힐 수 없다). 인프라 장애일 수 있어 상태는 PENDING
            // 으로 남긴다 — 일시 장애로 사용자가 신청한 하향을 영구히 잃게 만들지 않는다.
            counts.failed++;
            log.warn("예약 하향 적용 실패 — scheduledChangeId={} exception={} message={}",
                    scheduledChangeId, e.getClass().getSimpleName(), e.getMessage(), e);
            return;
        }

        recordOutcome(scheduledChangeId, result, counts);
    }

    /**
     * 도메인 예외 분류 — 활성 구독 경합만 "다음 회차에 자연 재시도"로 스킵하고, 나머지 ErrorCode 는
     * 예약을 <b>FAILED 로 종료</b>시키며 코드를 로그에 남긴다(클래스 javadoc 의 실패 분류 참고).
     */
    private void handleBusinessFailure(Long scheduledChangeId, BusinessException e, BatchCounts counts) {
        if (e.getErrorCode() == ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT) {
            counts.skipped++;
            log.info("예약 하향 적용 스킵(활성 구독 경합) — scheduledChangeId={}", scheduledChangeId);
            return;
        }
        counts.failed++;
        // ⚠️ 실행 트랜잭션은 이미 롤백됐으므로 별도 트랜잭션(REQUIRES_NEW)에서 FAILED 를 기록한다.
        // 이 기록마저 실패하면 예약은 PENDING 으로 남아 다음 회차에 다시 시도된다(같은 예외 반복).
        String failureReason = "errorCode=" + e.getErrorCode().name();
        ScheduledPlanChangeFailure failure;
        try {
            failure = scheduledPlanChangeWriter.markFailed(scheduledChangeId, failureReason);
        } catch (Exception markFailure) {
            failure = ScheduledPlanChangeFailure.notMarked();
            log.warn("예약 하향 FAILED 기록 실패 — scheduledChangeId={} exception={}",
                    scheduledChangeId, markFailure.getClass().getSimpleName());
        }
        // ErrorCode 를 반드시 남긴다 — FREE 는 max_seats=1 이라 owner 가 ACTIVE ADMIN 이 아닌 회사는
        // requireSurvivingActiveAdmin 에 걸리는 등, 코드를 버리면 운영자가 원인을 특정할 수 없다.
        log.warn("예약 하향 적용 실패 — scheduledChangeId={} errorCode={} message={} failedMarked={}",
                scheduledChangeId, e.getErrorCode(), e.getErrorCode().getMessage(), failure.marked());
        // ⚠️ 실패 알림(리뷰 P2-5) — FAILED 는 종료 상태라 재시도가 없고 조회는 PENDING 만 노출한다.
        // 알리지 않으면 신청자에게는 "예약을 걸었는데 어느 날 조용히 사라지고 요금제는 그대로"가 된다.
        if (publishFailureNotification(scheduledChangeId, failure, failureReason)) {
            counts.notified++;
        }
    }

    /**
     * 실패 알림 1건 발행 — <b>FAILED 기록이 커밋된 뒤</b>에 호출된다. 이번 실행이 실제로 종료시킨
     * 경우에만 보내 중복 통지를 막는다. 발행 실패는 이미 확정된 상태 기록을 되돌리지 않고 WARN 으로만
     * 표면화한다(적용 알림과 같은 원칙).
     */
    private boolean publishFailureNotification(Long scheduledChangeId, ScheduledPlanChangeFailure failure,
            String failureReason) {
        if (!failure.marked()) {
            return false;
        }
        if (failure.recipientUserId() == null) {
            log.warn("예약 하향 실패 알림 수신자 없음 — scheduledChangeId={}", scheduledChangeId);
            return false;
        }
        try {
            notificationService.notify(failure.recipientUserId(), NotificationType.PLAN_DOWNGRADE_FAILED,
                    ScheduledPlanChangeNotificationPayload.serializeFailed(failure, failureReason));
            return true;
        } catch (Exception e) {
            log.warn("예약 하향 실패 알림 발행 실패 — scheduledChangeId={} exception={}",
                    scheduledChangeId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** 실행 결과를 집계하고, 실제로 적용됐으면 알림을 1건 발행한다. */
    private void recordOutcome(Long scheduledChangeId, ScheduledPlanChangeResult result, BatchCounts counts) {
        if (result.canceled()) {
            counts.canceled++;
            log.info("예약 하향 무효 취소 — scheduledChangeId={} reason={}", scheduledChangeId, result.reason());
            return;
        }
        if (!result.applied()) {
            counts.skipped++;
            log.info("예약 하향 적용 스킵 — scheduledChangeId={} reason={}", scheduledChangeId, result.reason());
            return;
        }
        counts.applied++;
        if (publishNotification(scheduledChangeId, result)) {
            counts.notified++;
        }
    }

    /**
     * 적용 알림 1건 발행 — <b>적용 트랜잭션이 커밋된 뒤</b>에 호출된다. 발행 실패가 이미 확정된 하향을
     * 되돌리지 않도록 예외를 삼키고 WARN 으로만 표면화한다(권한은 내려갔는데 알림이 없는 상태는 그
     * 반대보다 낫다 — 반대는 사용자가 멀쩡한 플랜을 잃었다고 오인한다).
     */
    private boolean publishNotification(Long scheduledChangeId, ScheduledPlanChangeResult result) {
        Long recipientUserId = result.recipientUserId();
        if (recipientUserId == null) {
            log.warn("예약 하향 알림 수신자 없음 — scheduledChangeId={} companyId={}",
                    scheduledChangeId, result.companyId());
            return false;
        }
        try {
            notificationService.notify(recipientUserId, NotificationType.PLAN_DOWNGRADED,
                    ScheduledPlanChangeNotificationPayload.serializeApplied(result));
            return true;
        } catch (Exception e) {
            log.warn("예약 하향 알림 발행 실패 — scheduledChangeId={} exception={}",
                    scheduledChangeId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 무결성 위반의 <b>값 없는</b> 식별 정보 — 제약명(Hibernate)과 SQLState(JDBC)만 뽑는다.
     * 예외 메시지 원문에는 위반 컬럼 값이 그대로 들어 있어 로그에 남기지 않는다
     * ({@code PlanExpiryScheduler#describeIntegrityViolation} 과 같은 규칙).
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

    /** 회차 집계(적용/무효취소/스킵/실패/알림 건수). */
    private static final class BatchCounts {
        private int applied;
        private int canceled;
        private int skipped;
        private int failed;
        private int notified;
    }
}
