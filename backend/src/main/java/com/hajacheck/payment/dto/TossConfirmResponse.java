package com.hajacheck.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 토스페이먼츠 결제 승인 API 응답(#988 / HAJA-489) — 필요한 필드만 받는다.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}: 응답에는 카드 정보·할부·현금영수증 등 우리가
 * 저장하지 않는 필드가 다수 포함된다. PG 가 필드를 추가해도 역직렬화가 깨지지 않아야 하고, <b>받지 않는
 * 필드는 애초에 우리 프로세스 메모리·로그에 남지 않는다</b>(카드정보 미보유 — 보안 요구 6).
 *
 * @param status     승인 결과 상태(정상 승인은 "DONE")
 * @param method     결제 수단 한글 표기("카드" 등) — 우리 enum 매핑은 {@code TossPaymentsClient} 가 한다
 * @param approvedAt ISO-8601 오프셋 문자열(예: 2026-07-27T10:00:00+09:00)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossConfirmResponse(
        String paymentKey,
        String orderId,
        String status,
        String method,
        String approvedAt,
        Receipt receipt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Receipt(String url) {
    }
}
