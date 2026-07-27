package com.hajacheck.payment.service;

/**
 * PG 승인 실패(#988 / HAJA-489) — {@code TossPaymentsClient} 가 던지고 {@code PaymentService} 만 잡는다.
 *
 * <p>{@link com.hajacheck.global.exception.BusinessException} 이 아니라 전용 예외인 이유: 호출부가 이
 * 실패를 <b>결제 원장에 반영한 뒤</b> 도메인 에러(PAYMENT_GATEWAY_ERROR)로 바꿔 던져야 하는데,
 * BusinessException 이면 GlobalExceptionHandler 로 그대로 새어 나가 그 단계를 건너뛸 수 있다.
 *
 * <p><b>⚠️ "확정 거절"과 "결과 불명"은 반드시 구분해야 한다</b>(리뷰 P1-C). 둘을 똑같이 FAILED 로
 * 확정하면, 토스에서 <b>승인은 성사됐는데 응답만 못 받은</b> 경우(타임아웃·응답 파싱 실패)에도 주문이
 * FAILED 로 닫혀 같은 orderId 재확정이 영구히 막힌다 — 돈은 나갔는데 플랜은 없고 복구 수단도 없다.
 * <ul>
 *   <li><b>확정 거절</b>({@link #isOutcomeUnknown()} = false): PG 가 실패 코드를 명시적으로 준 경우.
 *       승인이 일어나지 않았음이 확실하므로 FAILED 로 확정해도 안전하다.</li>
 *   <li><b>결과 불명</b>({@link #isOutcomeUnknown()} = true): {@link #CODE_UNREACHABLE}(연결 실패·타임아웃),
 *       {@link #CODE_INVALID_RESPONSE}(응답 해석 불가). 주문을 <b>READY 로 남겨</b> 재확정 여지를 지킨다.</li>
 *   <li><b>이미 처리됨</b>({@link #isAlreadyProcessed()} = true): 같은 주문에 대한 동시 confirm 경합에서 진 쪽.
 *       호출부가 결제 행을 다시 읽어 PAID 면 <b>성공(멱등)</b>으로 응답한다 — 실제로 결제는 성사됐으므로
 *       502 를 돌려주면 사용자가 재결제해 중복 청구로 이어진다.</li>
 * </ul>
 */
public class TossPaymentApprovalException extends RuntimeException {

    /** 연결 실패·타임아웃 등 응답 자체를 받지 못한 경우의 자체 코드(PG 코드 체계와 충돌하지 않는 접두사). */
    public static final String CODE_UNREACHABLE = "HAJA_GATEWAY_UNREACHABLE";
    /** 응답은 받았으나 형식이 해석 불가하거나 승인 완료 상태가 아닌 경우. */
    public static final String CODE_INVALID_RESPONSE = "HAJA_GATEWAY_INVALID_RESPONSE";
    /** 토스가 "이미 처리된 결제"로 거절할 때의 코드 — 같은 주문 동시 confirm 경합의 신호다. */
    public static final String CODE_ALREADY_PROCESSED = "ALREADY_PROCESSED_PAYMENT";

    /**
     * PG 실패 코드(payments.failure_code 로 기록) — 통신 실패는 위 자체 정의 코드를 쓴다.
     */
    private final String code;

    /**
     * PG 실패 사유. <b>PG 가 준 문구만</b> 담고 요청 바디·시크릿·paymentKey 는 담지 않는다
     * (이 값은 DB 에 저장되고 운영자가 열람한다).
     */
    private final String safeMessage;

    private final boolean outcomeUnknown;
    private final boolean alreadyProcessed;

    private TossPaymentApprovalException(String code, String safeMessage, boolean outcomeUnknown,
                                         boolean alreadyProcessed) {
        // 예외 메시지에도 코드만 남긴다 — 스택이 로깅되더라도 민감정보가 실리지 않게.
        super("toss payment approval failed: " + code);
        this.code = code;
        this.safeMessage = safeMessage;
        this.outcomeUnknown = outcomeUnknown;
        this.alreadyProcessed = alreadyProcessed;
    }

    /**
     * PG 가 실패 코드를 명시한 <b>확정 거절</b>. 승인이 일어나지 않았음이 확실하므로 호출부가 FAILED 로
     * 확정한다. 단 "이미 처리된 결제"는 거절이 아니라 <b>이미 성공했다는 신호</b>라 별도로 표시한다.
     */
    public static TossPaymentApprovalException rejected(String code, String safeMessage) {
        return new TossPaymentApprovalException(
                code, safeMessage, false, CODE_ALREADY_PROCESSED.equals(code));
    }

    /**
     * 승인 <b>결과를 알 수 없는</b> 실패(타임아웃·연결 실패·응답 해석 불가). 호출부는 주문을 READY 로 남겨
     * 재확정이 가능하도록 해야 한다 — 승인이 이미 성사됐을 수 있기 때문이다.
     */
    public static TossPaymentApprovalException outcomeUnknown(String code, String safeMessage) {
        return new TossPaymentApprovalException(code, safeMessage, true, false);
    }

    public String getCode() {
        return code;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    /** {@code true} 면 승인 여부 불명 — FAILED 로 확정하면 안 된다(재확정 경로를 막아버린다). */
    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }

    /** {@code true} 면 PG 기준으로 이미 승인된 결제 — 호출부가 원장을 재조회해 멱등 성공으로 응답한다. */
    public boolean isAlreadyProcessed() {
        return alreadyProcessed;
    }
}
