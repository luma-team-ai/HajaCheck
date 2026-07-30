package com.hajacheck.payment.service;

/**
 * 승인 호출 직전 검증 결과(#988 / HAJA-489) — {@code PaymentWriter#prepareConfirm} 이 트랜잭션 안에서
 * 계산해 트랜잭션 밖의 {@code PaymentService} 로 넘기는 값. 엔티티를 트랜잭션 밖으로 내보내지 않기 위해
 * 필요한 스칼라만 담는다(지연 로딩·detached 상태 오해 방지).
 *
 * @param paymentId             승인 결과를 반영할 결제 행 id
 * @param outcome               판정 결과({@link PaymentConfirmOutcome})
 * @param planApplicationPending 승인은 끝났는데 플랜 전이가 남아 있는 상태 — 재요청이 전이만 재시도할 근거
 * @param amount                <b>서버가 사전 등록한</b> 청구 금액. PG 승인 호출에는 이 값만 쓴다(보안 요구 1)
 */
public record PaymentConfirmPreparation(
        Long paymentId,
        PaymentConfirmOutcome outcome,
        boolean planApplicationPending,
        long amount) {

    public static PaymentConfirmPreparation alreadyPaid(Long paymentId, boolean planApplicationPending) {
        return new PaymentConfirmPreparation(
                paymentId, PaymentConfirmOutcome.ALREADY_PAID, planApplicationPending, 0L);
    }

    public static PaymentConfirmPreparation readyToApprove(Long paymentId, long amount) {
        return new PaymentConfirmPreparation(
                paymentId, PaymentConfirmOutcome.READY_TO_APPROVE, false, amount);
    }

    public static PaymentConfirmPreparation expired(Long paymentId) {
        return new PaymentConfirmPreparation(paymentId, PaymentConfirmOutcome.EXPIRED, false, 0L);
    }

    public static PaymentConfirmPreparation attemptLimitExceeded(Long paymentId) {
        return new PaymentConfirmPreparation(
                paymentId, PaymentConfirmOutcome.ATTEMPT_LIMIT_EXCEEDED, false, 0L);
    }

    /** 이미 승인된 주문(=재요청). {@code true} 면 PG 를 다시 호출하면 안 된다(보안 요구 3). */
    public boolean alreadyPaid() {
        return outcome == PaymentConfirmOutcome.ALREADY_PAID;
    }

    /**
     * 주문을 닫는 쓰기가 필요한 거절인지 — 호출부가 <b>별도 트랜잭션</b>으로 취소를 커밋한 뒤 404 를
     * 던져야 한다. 검증 트랜잭션 안에서 쓰고 예외를 던지면 그 쓰기가 롤백된다(리뷰 P2).
     */
    public boolean requiresClosing() {
        return outcome == PaymentConfirmOutcome.EXPIRED
                || outcome == PaymentConfirmOutcome.ATTEMPT_LIMIT_EXCEEDED;
    }
}
