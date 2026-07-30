package com.hajacheck.payment.entity;

/**
 * 결제 상태 — DDL payment_status_type(PG named enum) 대응(#988 / HAJA-489).
 *
 * <p>전이는 {@code READY → PAID} 또는 {@code READY → FAILED} 단방향이다. PAID 는 종착점이며 어떤 경로로도
 * 다시 내려가지 않는다 — 이미 승인된 결제를 실패로 덮으면 중복 승인 경합(같은 orderId 동시 confirm)에서
 * 돈이 빠져나간 기록이 사라진다.
 *
 * <p>CANCELED 는 결제 취소(환불) 결과를 표현하기 위해 스키마에 미리 자리를 잡아둔 값이다 — 환불·부분취소는
 * 이번 범위 밖이라 이 값을 쓰는 코드 경로는 아직 없다.
 */
public enum PaymentStatus {
    READY,
    PAID,
    FAILED,
    CANCELED
}
