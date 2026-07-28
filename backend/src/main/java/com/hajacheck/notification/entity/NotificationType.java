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
    PLAN_EXPIRED
}
