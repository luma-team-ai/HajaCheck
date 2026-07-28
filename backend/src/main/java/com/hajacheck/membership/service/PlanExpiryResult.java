package com.hajacheck.membership.service;

import com.hajacheck.membership.entity.PlanName;
import java.time.Instant;

/**
 * 구독 1건에 대한 만료 강등 처리 결과(#1145 / HAJA-549) — {@link PlanExpiryWriter} 가 반환하고
 * {@code PlanExpiryScheduler} 가 알림 발행·집계에 쓴다.
 *
 * <p>알림 발행에 필요한 값을 여기에 담아 <b>트랜잭션 밖으로 들고 나오는</b> 이유: 알림은 강등이 실제로
 * 커밋된 뒤에 나가야 한다. 강등 트랜잭션(REQUIRES_NEW) 안에서 발행하면 그 트랜잭션이 롤백돼도
 * 알림({@code NotificationService#notify} 역시 REQUIRES_NEW)은 이미 커밋돼 "권한은 그대로인데 강등
 * 알림만 받는" 상태가 된다.
 *
 * @param downgraded         실제로 FREE 강등이 일어났는지. false면 대상 조건이 이미 해소돼 아무것도
 *                           하지 않은 것이다(스킵 — 아래 {@code skipReason} 참고).
 * @param recipientUserId    알림 수신자. 회사 구독이면 회사 owner, 개인 구독이면 구독 소유 사용자.
 * @param previousPlanName   강등 전 요금제(알림 본문·운영 로그용).
 * @param companyId          회사 구독이면 회사 id, 개인 구독이면 {@code null}.
 * @param userId             개인 구독이면 사용자 id, 회사 구독이면 {@code null}(owner XOR).
 * @param periodEndAt        만료된 결제 주기 종료 시각.
 * @param suspendedSeatCount 이 강등으로 정지된 좌석 수(개인 구독은 항상 0 — 좌석 개념이 없다).
 * @param skipReason         {@code downgraded=false} 일 때의 사유(로깅 전용). 강등됐으면 {@code null}.
 */
public record PlanExpiryResult(
        boolean downgraded,
        Long recipientUserId,
        PlanName previousPlanName,
        Long companyId,
        Long userId,
        Instant periodEndAt,
        int suspendedSeatCount,
        String skipReason) {

    /** 대상 조건이 이미 해소된 구독 — 아무것도 바꾸지 않았다(재조회~처리 사이에 다른 경로가 전이시킨 경우 등). */
    public static PlanExpiryResult skipped(String skipReason) {
        return new PlanExpiryResult(false, null, null, null, null, null, 0, skipReason);
    }

    public static PlanExpiryResult downgraded(Long recipientUserId, PlanName previousPlanName,
            Long companyId, Long userId, Instant periodEndAt, int suspendedSeatCount) {
        return new PlanExpiryResult(true, recipientUserId, previousPlanName, companyId, userId,
                periodEndAt, suspendedSeatCount, null);
    }
}
