package com.hajacheck.notification.entity;

/**
 * PostgreSQL {@code notification_type}과 일치하는 알림 유형.
 *
 * <p>⚠️ 값을 추가할 때는 <b>반드시 Flyway 마이그레이션으로 PG enum 라벨도 함께 추가</b>해야 한다
 * ({@code alter type notification_type add value if not exists '...'}). 코드에만 추가하면 그 유형의
 * 알림 INSERT 가 런타임에 실패한다(#534가 role_type 에서 겪은 것과 같은 형태).
 */
public enum NotificationType {
    ANALYSIS_DONE,
    REVIEW_PENDING,
    COUNSEL_REPLIED,
    INSPECTION_DUE,
    /** 구독 결제 주기 만료로 FREE 자동 강등됨(#1145 / HAJA-549) — Flyway V28이 PG 라벨을 추가한다. */
    PLAN_EXPIRED,
    /**
     * 신청해 둔 플랜 하향 예약이 결제 주기 종료 시점에 적용됨(#1105 / HAJA-526) — Flyway V31이 PG 라벨을
     * 추가한다.
     *
     * <p>{@link #PLAN_EXPIRED}와 합치지 않는 이유: 사용자에게 전혀 다른 사건이다. PLAN_EXPIRED 는
     * "결제 주기가 끝나 FREE로 내려갔다"(신청한 적 없음)이고, 이 유형은 "내가 신청한 하향이 예정대로
     * 적용됐다"라 문구·후속 안내가 갈린다.
     */
    PLAN_DOWNGRADED,
    /**
     * 신청해 둔 플랜 하향 예약이 <b>실패로 종료</b>됨(#1105 / HAJA-526) — Flyway V31이 PG 라벨을 추가한다.
     *
     * <p>FAILED 는 종료 상태라 자동 재시도가 없고, 조회({@code AdminPlanResponse.scheduledChange})는
     * 대기(PENDING) 예약만 노출한다. 그래서 알림이 없으면 신청자에게는 "예약을 걸었는데 어느 날 조용히
     * 사라지고 요금제는 그대로"가 된다 — 서버 로그에만 남기면 사용자는 영원히 알 수 없다.
     */
    PLAN_DOWNGRADE_FAILED
}
