package com.hajacheck.payment.dto;

import com.hajacheck.payment.entity.Payment;

/**
 * POST /api/me/plan/orders 응답(#988 / HAJA-489) — 프론트가 토스 결제창에 그대로 넘길 값.
 *
 * <p><b>일할 크레딧(#1146 / HAJA-550)</b>: 상향 결제 시 현재 플랜의 잔여 기간을 크레딧으로 차감한
 * 금액이 {@code amount} 다. {@code listPrice - credit == amount} 가 항상 성립하도록 서버가 계산해
 * 내려준다 — 결제 화면이 "정가 / 크레딧 / 실청구액" 3줄을 안내 없이 금액만 바뀌는 문의를 만들지 않고
 * 그대로 표시할 수 있게 하기 위해서다.
 *
 * @param orderId   서버가 발급한 주문 식별자(클라이언트 생성 금지)
 * @param planName  결제 대상 요금제명
 * @param listPrice 대상 플랜의 정가({@code plans.price_monthly}, 크레딧 차감 전)
 * @param credit    잔여 기간 일할 크레딧(차감액, 항상 0 이상) — FREE→유료·주기 정보 없음·만료 경과면 0
 * @param amount    서버가 결정한 최종 청구 금액({@code listPrice - credit}, 최소 결제금액 가드 적용 후).
 *                  원화는 소수점이 없고 토스 결제창·승인 API 도 정수를 요구하므로 {@code long} 으로
 *                  노출한다(엔티티는 numeric(10,2) 스냅샷을 그대로 보관).
 * @param orderName 결제창에 표시할 주문명
 */
public record PaymentOrderResponse(
        String orderId, String planName, long listPrice, long credit, long amount, String orderName) {

    public static PaymentOrderResponse of(
            Payment payment, long listPrice, long credit, long amount, String orderName) {
        return new PaymentOrderResponse(
                payment.getOrderId(), payment.getPlanName().name(), listPrice, credit, amount, orderName);
    }
}
