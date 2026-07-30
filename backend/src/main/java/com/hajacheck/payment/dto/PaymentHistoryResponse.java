package com.hajacheck.payment.dto;

import com.hajacheck.payment.entity.Payment;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/me/payments 응답(#988 / HAJA-489) — 마이페이지 결제 내역 모달(#864 해소). 최신순.
 *
 * <p>PG 결제 키({@code payment_key})는 <b>의도적으로 노출하지 않는다</b> — 화면에 필요 없고, 유출되면
 * 결제 조회·취소 API 의 식별자로 쓰일 수 있다(보안 요구 6).
 */
public record PaymentHistoryResponse(List<PaymentItem> payments) {

    /**
     * @param amount   원화 정수(주문 응답과 동일 표현)
     * @param method   승인된 결제 수단. 미승인이거나 매핑 밖 수단이면 null
     * @param receiptUrl PG 영수증 URL. 미승인이면 null
     */
    public record PaymentItem(
            Long id,
            String orderId,
            String planName,
            long amount,
            String status,
            String method,
            Instant approvedAt,
            String receiptUrl) {

        public static PaymentItem from(Payment payment) {
            return new PaymentItem(
                    payment.getId(),
                    payment.getOrderId(),
                    payment.getPlanName().name(),
                    // 금액 스냅샷은 numeric(10,2)지만 원화라 소수부가 없다. 혹시 소수부가 있는 데이터가
                    // 섞여도 이력 조회가 500으로 죽지 않도록 내림 후 long 으로 변환한다(표시 전용).
                    payment.getAmount().longValue(),
                    payment.getStatus().name(),
                    payment.getMethod() == null ? null : payment.getMethod().name(),
                    payment.getApprovedAt(),
                    payment.getReceiptUrl());
        }
    }

    public static PaymentHistoryResponse from(List<Payment> payments) {
        return new PaymentHistoryResponse(payments.stream().map(PaymentItem::from).toList());
    }
}
