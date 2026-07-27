package com.hajacheck.payment.dto;

import com.hajacheck.payment.entity.Payment;

/**
 * POST /api/me/plan/orders 응답(#988 / HAJA-489) — 프론트가 토스 결제창에 그대로 넘길 값.
 *
 * @param orderId   서버가 발급한 주문 식별자(클라이언트 생성 금지)
 * @param planName  결제 대상 요금제명
 * @param amount    서버가 결정한 청구 금액. 원화는 소수점이 없고 토스 결제창·승인 API 도 정수를 요구하므로
 *                  {@code long} 으로 노출한다(엔티티는 numeric(10,2) 스냅샷을 그대로 보관).
 * @param orderName 결제창에 표시할 주문명
 */
public record PaymentOrderResponse(String orderId, String planName, long amount, String orderName) {

    public static PaymentOrderResponse of(Payment payment, long amount, String orderName) {
        return new PaymentOrderResponse(payment.getOrderId(), payment.getPlanName().name(), amount, orderName);
    }
}
