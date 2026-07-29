package com.hajacheck.membership.service;

import com.hajacheck.membership.entity.PlanName;
import java.time.Instant;
import java.util.List;

/**
 * 예약 하향 1건에 대한 실행 결과(#1105 / HAJA-526) — {@link ScheduledPlanChangeWriter} 가 반환하고
 * {@code ScheduledPlanChangeScheduler} 가 알림 발행·집계에 쓴다.
 *
 * <p>알림 발행에 필요한 값을 여기에 담아 <b>트랜잭션 밖으로 들고 나오는</b> 이유는 {@link PlanExpiryResult}
 * 와 같다: 알림은 하향이 실제로 커밋된 뒤에 나가야 한다. 실행 트랜잭션(REQUIRES_NEW) 안에서 발행하면 그
 * 트랜잭션이 롤백돼도 알림({@code NotificationService#notify} 역시 REQUIRES_NEW)은 이미 커밋돼 "요금제는
 * 그대로인데 하향 알림만 받는" 상태가 된다.
 *
 * @param applied           실제로 하향이 적용됐는지. false면 아무것도 바꾸지 않았다는 뜻이다.
 * @param canceled          예약이 <b>무효</b>로 판정돼 CANCELED 로 종료됐는지(구독이 이미 다른 경로로
 *                          전이됨 등). 실패가 아니라 정상 종료라 집계·로그 수준을 분리한다.
 * @param recipientUserId   알림 수신자. 회사 구독이면 회사 owner, 개인 구독이면 구독 소유 사용자.
 * @param previousPlanName  하향 전 요금제(알림 본문·운영 로그용).
 * @param targetPlanName    하향 후 요금제.
 * @param companyId         회사 구독이면 회사 id, 개인 구독이면 {@code null}.
 * @param userId            개인 구독이면 사용자 id, 회사 구독이면 {@code null}(owner XOR).
 * @param effectiveAt       예약 적용 기준 시각(= 예약 시점의 결제 주기 종료 시각).
 * @param suspendedUserIds  이 하향으로 정지된 좌석의 사용자 id. <b>건수가 아니라 id 목록</b>인 이유는
 *                          {@link PlanExpiryResult} 와 같다(리뷰 P2-3) — 오적용이 발생하면 되돌릴 대상을
 *                          이 값 말고는 복원할 수 없다({@code users.status} 는 이전 값을 보관하지 않는다).
 *                          id 만 담으므로 개인정보는 포함되지 않는다.
 * @param reason            {@code applied=false} 일 때의 사유(로깅·failure_reason 전용).
 */
public record ScheduledPlanChangeResult(
        boolean applied,
        boolean canceled,
        Long recipientUserId,
        PlanName previousPlanName,
        PlanName targetPlanName,
        Long companyId,
        Long userId,
        Instant effectiveAt,
        List<Long> suspendedUserIds,
        String reason) {

    public ScheduledPlanChangeResult {
        suspendedUserIds = suspendedUserIds == null ? List.of() : List.copyOf(suspendedUserIds);
    }

    /** 아무것도 바꾸지 않고 지나간 예약(이미 처리됨·다른 실행이 점유함 등) — 상태도 그대로 둔다. */
    public static ScheduledPlanChangeResult skipped(String reason) {
        return new ScheduledPlanChangeResult(false, false, null, null, null, null, null, null,
                List.of(), reason);
    }

    /** 예약이 의미를 잃어 CANCELED 로 종료된 경우(구독이 이미 전이됨·더 이상 하향이 아님 등). */
    public static ScheduledPlanChangeResult canceled(String reason) {
        return new ScheduledPlanChangeResult(false, true, null, null, null, null, null, null,
                List.of(), reason);
    }

    public static ScheduledPlanChangeResult applied(Long recipientUserId, PlanName previousPlanName,
            PlanName targetPlanName, Long companyId, Long userId, Instant effectiveAt,
            List<Long> suspendedUserIds) {
        return new ScheduledPlanChangeResult(true, false, recipientUserId, previousPlanName,
                targetPlanName, companyId, userId, effectiveAt, suspendedUserIds, null);
    }

    /** 알림 payload·집계용 정지 좌석 수. */
    public int suspendedSeatCount() {
        return suspendedUserIds.size();
    }
}
