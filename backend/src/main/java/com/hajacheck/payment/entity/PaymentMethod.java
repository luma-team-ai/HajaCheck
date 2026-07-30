package com.hajacheck.payment.entity;

/**
 * 결제 수단 — DDL payment_method_type(PG named enum) 대응(#988 / HAJA-489).
 *
 * <p>이번 범위는 카드 결제 단일이다(간편결제·계좌이체·가상계좌는 범위 밖). 토스페이먼츠 승인 응답의
 * {@code method} 는 "카드"·"간편결제" 같은 한글 표기 문자열이라 이 enum 으로 직접 역직렬화하지 않고
 * {@code TossPaymentsClient} 가 명시적으로 매핑한다 — 매핑되지 않는 수단은 {@code null}(컬럼 nullable)로
 * 남기고 결제 자체는 정상 처리한다(수단 표기 하나 때문에 승인된 결제를 실패로 만들지 않는다).
 */
public enum PaymentMethod {
    CARD
}
