package com.hajacheck.payment.service;

/**
 * 승인 전 검증의 판정 결과(#988) — {@code PaymentWriter#prepareConfirm} 이 <b>읽기 전용으로</b> 내리는 결론.
 *
 * <p>거절 중에도 <b>주문을 닫는 쓰기가 필요한 경우</b>({@link #EXPIRED}·{@link #ATTEMPT_LIMIT_EXCEEDED})가
 * 있어서 열거형으로 돌려준다. 검증 트랜잭션 안에서 쓰고 곧바로 예외를 던지면 그 쓰기가
 * <b>롤백되어 저장되지 않기 때문이다</b>(리뷰 P2 — {@code BusinessException} 은 RuntimeException 이라
 * Spring 기본 규칙으로 롤백된다). 그래서 판정만 여기서 하고, 실제 취소 기록은 트랜잭션 밖의
 * {@code PaymentService} 가 별도 트랜잭션({@code REQUIRES_NEW})으로 남긴 뒤 404 를 던진다.
 *
 * <p>쓰기가 필요 없는 거절(미존재·타인 소유·금액 불일치·동일 플랜 등)은 그대로 예외로 던진다.
 */
public enum PaymentConfirmOutcome {

    /** PG 승인을 호출해도 되는 상태. */
    READY_TO_APPROVE,

    /** 이미 승인된 주문 — PG 재호출 없이 멱등 응답한다. */
    ALREADY_PAID,

    /** 유효시간(TTL) 초과 — 주문을 CANCELED 로 닫은 뒤 404. */
    EXPIRED,

    /** 승인 시도 횟수 상한 초과 — 주문을 CANCELED 로 닫은 뒤 404. */
    ATTEMPT_LIMIT_EXCEEDED
}
