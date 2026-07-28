package com.hajacheck.membership.service;

import com.hajacheck.membership.entity.PlanName;

/**
 * 예약 하향을 FAILED 로 종료한 결과(#1105 / HAJA-526, 리뷰 P2-5) —
 * {@link ScheduledPlanChangeWriter#markFailed} 가 반환하고 {@code ScheduledPlanChangeScheduler} 가
 * <b>실패 알림</b> 발행에 쓴다.
 *
 * <p>알림에 필요한 값을 여기에 담아 트랜잭션 밖으로 들고 나오는 이유는 적용 결과
 * ({@link ScheduledPlanChangeResult})와 같다 — 알림은 상태 기록이 커밋된 뒤에 나가야 한다.
 *
 * @param marked          실제로 PENDING → FAILED 로 바뀌었는지. false면 이미 다른 상태(적용·취소)라
 *                        이번 실행이 종료시킨 것이 아니므로 <b>알림도 발행하지 않는다</b>(중복 통지 방지).
 * @param recipientUserId 알림 수신자(회사 구독이면 owner, 개인 구독이면 본인). 해석 실패 시 {@code null}
 *                        — 그때는 호출부가 WARN 만 남긴다(FAILED 기록 자체는 되돌리지 않는다).
 * @param targetPlanName  예약 대상 요금제(알림 본문용). 해석 실패 시 {@code null}.
 */
public record ScheduledPlanChangeFailure(boolean marked, Long recipientUserId, PlanName targetPlanName) {

    /** 상태가 이미 바뀌어 이번 실행이 종료시키지 않은 경우 — 알림 대상이 아니다. */
    public static ScheduledPlanChangeFailure notMarked() {
        return new ScheduledPlanChangeFailure(false, null, null);
    }

    /** 알림을 발행해야 하는가 — 이번 실행이 종료시켰고 수신자를 알아냈을 때만. */
    public boolean notifiable() {
        return marked && recipientUserId != null;
    }
}
