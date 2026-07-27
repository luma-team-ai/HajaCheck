package com.hajacheck.payment.dto;

/**
 * 토스페이먼츠 결제 승인 API 요청 바디(#988 / HAJA-489).
 *
 * <p>{@code amount} 는 <b>서버가 사전 등록한 주문 금액</b>을 그대로 보낸다(클라이언트가 보낸 값이 아니다 —
 * 클라이언트 값은 그 전에 대조에만 쓰인다). 원화는 소수부가 없어 {@code long} 으로 직렬화한다.
 */
public record TossConfirmRequest(String paymentKey, String orderId, long amount) {
}
