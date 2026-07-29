package com.hajacheck.membership.entity;

/**
 * 플랜 하향 예약 상태(#1105 / HAJA-526) — PostgreSQL {@code scheduled_plan_change_status_type} 과 1:1.
 *
 * <p>⚠️ 값을 추가할 때는 <b>반드시 Flyway 마이그레이션으로 PG enum 라벨도 함께 추가</b>해야 한다
 * ({@code alter type scheduled_plan_change_status_type add value if not exists '...'}). 코드에만
 * 추가하면 그 상태로의 UPDATE 가 런타임에 실패한다(#534가 role_type 에서 겪은 것과 같은 형태).
 *
 * <p>{@link #PENDING} 만 스케줄러의 실행 대상이며, 나머지 셋은 <b>종료 상태</b>다 — 한 번 벗어나면
 * 다시 PENDING 으로 돌아오지 않는다. 재시도가 필요한 실패(잠금 경합·활성 구독 경합 등)는 상태를 바꾸지
 * 않고 PENDING 으로 남겨 다음 회차가 자연 재시도한다({@code ScheduledPlanChangeWriter} javadoc 참고).
 */
public enum ScheduledPlanChangeStatus {
    /** 대기 — 아직 적용되지 않았고 {@code effective_at} 이 되면 스케줄러가 실행한다. */
    PENDING,
    /** 적용됨 — 하향 전이와 초과 좌석 정지가 커밋됐다. */
    APPLIED,
    /** 취소됨 — 신청자가 직접 취소했거나, 구독이 다른 경로로 전이돼 예약이 무효가 됐다. */
    CANCELED,
    /** 실패 — 사람이 원인을 확인해야 하는 종료 실패({@code failure_reason} 참고). 자동 재시도하지 않는다. */
    FAILED
}
