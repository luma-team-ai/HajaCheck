package com.hajacheck.payment.service;

/**
 * PG 승인 실패(#988 / HAJA-489) — {@code TossPaymentsClient} 가 던지고 {@code PaymentService} 만 잡는다.
 *
 * <p>{@link com.hajacheck.global.exception.BusinessException} 이 아니라 전용 예외인 이유: 호출부가 이
 * 실패를 <b>결제 원장에 FAILED 로 기록한 뒤</b> 도메인 에러(PAYMENT_GATEWAY_ERROR)로 바꿔 던져야 하는데,
 * BusinessException 이면 GlobalExceptionHandler 로 그대로 새어 나가 기록 단계를 건너뛸 수 있다.
 *
 * @param code    PG 실패 코드(payments.failure_code 로 기록) — 통신 실패는 자체 정의 코드를 쓴다
 * @param safeMessage PG 실패 사유. <b>PG 가 준 문구만</b> 담고 요청 바디·시크릿·paymentKey 는 담지 않는다
 *                    (이 값은 DB 에 저장되고 운영자가 열람한다).
 */
public class TossPaymentApprovalException extends RuntimeException {

    /** 연결 실패·타임아웃 등 응답 자체를 받지 못한 경우의 자체 코드(PG 코드 체계와 충돌하지 않는 접두사). */
    public static final String CODE_UNREACHABLE = "HAJA_GATEWAY_UNREACHABLE";
    /** 응답은 받았으나 형식이 해석 불가하거나 승인 완료 상태가 아닌 경우. */
    public static final String CODE_INVALID_RESPONSE = "HAJA_GATEWAY_INVALID_RESPONSE";

    private final String code;
    private final String safeMessage;

    public TossPaymentApprovalException(String code, String safeMessage) {
        // 예외 메시지에도 코드만 남긴다 — 스택이 로깅되더라도 민감정보가 실리지 않게.
        super("toss payment approval failed: " + code);
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public String getCode() {
        return code;
    }

    public String getSafeMessage() {
        return safeMessage;
    }
}
