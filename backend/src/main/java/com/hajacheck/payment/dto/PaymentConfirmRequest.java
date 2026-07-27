package com.hajacheck.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POST /api/me/payments/confirm 요청(#988 / HAJA-489) — 토스 결제창이 성공 리다이렉트로 돌려준 값.
 *
 * <p><b>이 요청의 값은 전부 신뢰하지 않는다.</b> {@code orderId} 로 서버가 사전 등록한 주문을 찾아
 * 소유자·상태를 검증하고, {@code amount} 는 저장된 금액과 <b>대조만</b> 한다(불일치면 PG 승인 호출 자체를
 * 하지 않고 PAYMENT_AMOUNT_MISMATCH). {@code paymentKey} 는 PG 승인 호출에만 쓰이고 로그·응답에 남기지
 * 않는다(보안 요구 6).
 *
 * @param amount 원화 정수. 소수를 보내면 역직렬화 단계에서 INVALID_INPUT(400)으로 떨어진다.
 */
public record PaymentConfirmRequest(
        // 길이 상한은 DDL 컬럼 폭과 동일하게 맞춘다 — 초과 입력이 DB 레벨에서 잘리거나 예외로 터지기 전에
        // 400(INVALID_INPUT)으로 걸러진다(리뷰 P3).
        @NotBlank @Size(max = 200) String paymentKey,
        @NotBlank @Size(max = 64) String orderId,
        @NotNull @Positive Long amount) {
}
