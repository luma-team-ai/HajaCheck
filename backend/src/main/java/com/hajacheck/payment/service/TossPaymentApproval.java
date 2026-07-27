package com.hajacheck.payment.service;

import com.hajacheck.payment.entity.PaymentMethod;
import java.time.Instant;

/**
 * PG 승인 성공 결과(#988 / HAJA-489) — 우리 도메인이 저장할 값만 담은 클라이언트 반환 타입.
 *
 * <p>PG 응답 원본(TossConfirmResponse)을 서비스 계층까지 흘리지 않는다: 저장 대상이 아닌 필드(카드 정보 등)가
 * 도메인 코드와 로그로 번지지 않게 경계에서 잘라낸다.
 *
 * @param method 매핑 밖 수단이면 {@code null}(수단 표기 때문에 승인된 결제를 실패시키지 않는다)
 */
public record TossPaymentApproval(
        String paymentKey,
        PaymentMethod method,
        String receiptUrl,
        Instant approvedAt) {
}
