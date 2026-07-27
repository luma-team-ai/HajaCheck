package com.hajacheck.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.dto.TossConfirmRequest;
import com.hajacheck.payment.dto.TossConfirmResponse;
import com.hajacheck.payment.entity.PaymentMethod;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 토스페이먼츠 결제 승인 클라이언트(#988 / HAJA-489) — {@code NtsBusinessVerifyClient} 의 골격을 그대로
 * 따른다(WebClient 금지, 내장 RestClient, 경계에서 예외 매핑, 개인정보·시크릿 미로깅).
 *
 * <p><b>진위확인과 결정적으로 다른 점 — fail-close</b>: 국세청 진위확인은 외부 장애 시 SKIPPED 로
 * fail-open 하지만, 결제는 그럴 수 없다. 승인 여부가 불명이면 <b>플랜을 주지 않는다</b>. 그래서 이
 * 클라이언트는 어떤 실패도 삼키지 않고 {@link TossPaymentApprovalException} 으로 표면화하며, 호출부
 * ({@code PaymentService})가 그 실패를 결제 원장에 FAILED 로 기록한 뒤 PAYMENT_GATEWAY_ERROR(502)로 바꾼다.
 *
 * <p><b>자동 재시도는 하지 않는다</b>: 승인은 멱등이 아닌 <b>돈이 움직이는 쓰기</b>다. 타임아웃 후
 * 곧바로 재호출하면 PG 쪽에서 이미 승인된 건을 다시 건드려 중복 청구·상태 불명을 키울 수 있다.
 *
 * <p><b>대신 "확정 거절"과 "결과 불명"을 구분해 던진다</b>(리뷰 P1-C). 타임아웃·연결 실패·응답 해석
 * 불가는 {@link TossPaymentApprovalException#outcomeUnknown}으로 표시해 <b>호출부가 주문을 FAILED 로
 * 닫지 않게</b> 한다 — 승인이 이미 성사된 뒤 응답만 못 받았을 수 있어서, 여기서 FAILED 로 확정하면
 * 같은 orderId 재확정이 영구히 막히고 돈만 나간 상태가 복구 불가로 굳는다. 실제 재확정은 사용자가 같은
 * orderId 로 다시 시도할 때 이뤄지며, 그때 PG 가 "이미 처리된 결제"로 답하면
 * {@link TossPaymentApprovalException#isAlreadyProcessed()} 경로가 멱등 성공으로 흡수한다.
 *
 * <p><b>로그 마스킹(보안 요구 6)</b>: {@code secretKey}·{@code paymentKey}·카드정보는 어떤 로그에도 남기지
 * 않는다. 남기는 것은 {@code orderId}(우리가 만든 식별자)와 PG 실패 코드·HTTP 상태뿐이다. 예외 스택도
 * 로깅하지 않는다 — RestClient 계열 예외 메시지에 요청 URL·응답 바디 스니펫이 실릴 수 있다.
 */
@Slf4j
@Component
public class TossPaymentsClient {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    /** 토스 승인 성공 상태값. 이 값이 아니면 승인 완료로 취급하지 않는다(부분 승인·대기 상태 방어). */
    private static final String STATUS_DONE = "DONE";
    /** 승인 응답 method 한글 표기 — 이번 범위는 카드만 저장한다. */
    private static final String METHOD_CARD = "카드";

    private final RestClient tossPaymentsRestClient;
    private final TossPaymentsProperties properties;
    private final ObjectMapper objectMapper;

    public TossPaymentsClient(@Qualifier("tossPaymentsRestClient") RestClient tossPaymentsRestClient,
                              TossPaymentsProperties properties,
                              ObjectMapper objectMapper) {
        this.tossPaymentsRestClient = tossPaymentsRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 시크릿 키가 주입됐는지 — 결제 진입점이 이 값으로 fail-close 판정을 한다(빈 문자열 인증 시도 금지). */
    public boolean isConfigured() {
        return StringUtils.hasText(properties.getSecretKey());
    }

    /**
     * 결제 승인. 성공하면 저장 대상 값만 담은 {@link TossPaymentApproval} 을 반환하고, 어떤 실패도
     * {@link TossPaymentApprovalException} 으로 던진다(삼키지 않는다).
     *
     * @param amount 서버가 사전 등록한 주문 금액(클라이언트 전달값 아님 — 보안 요구 1·2)
     */
    public TossPaymentApproval confirm(String paymentKey, String orderId, long amount) {
        TossConfirmResponse response;
        try {
            response = tossPaymentsRestClient.post()
                    .uri(CONFIRM_PATH)
                    .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossConfirmRequest(paymentKey, orderId, amount))
                    .retrieve()
                    .body(TossConfirmResponse.class);
        } catch (ResourceAccessException e) {
            // 연결 실패·타임아웃 — 승인 여부 불명. 재시도하지 않고 실패로 확정한다(클래스 javadoc).
            log.warn("결제 승인 외부 호출 실패(orderId={}, cause={}) — 승인 미확정으로 실패 처리",
                    orderId, e.getClass().getSimpleName());
            throw TossPaymentApprovalException.outcomeUnknown(
                    TossPaymentApprovalException.CODE_UNREACHABLE, "결제 서버에 연결하지 못했습니다.");
        } catch (RestClientResponseException e) {
            // PG 가 HTTP 오류로 응답 — 바디의 {code, message} 만 뽑아 기록한다(바디 전문 로깅 금지).
            PgError error = parsePgError(e);
            log.warn("결제 승인 거부(orderId={}, status={}, code={})",
                    orderId, e.getStatusCode().value(), error.code());
            throw TossPaymentApprovalException.rejected(error.code(), error.message());
        } catch (RestClientException e) {
            // 응답 역직렬화 실패 등 — 예외 메시지에 응답 바디 스니펫이 실릴 수 있어 스택을 로깅하지 않는다.
            log.warn("결제 승인 응답 처리 실패(orderId={}, cause={})", orderId, e.getClass().getSimpleName());
            throw TossPaymentApprovalException.outcomeUnknown(
                    TossPaymentApprovalException.CODE_INVALID_RESPONSE, "결제 서버 응답을 처리할 수 없습니다.");
        }

        return interpret(orderId, response);
    }

    private TossPaymentApproval interpret(String orderId, TossConfirmResponse response) {
        if (response == null || !StringUtils.hasText(response.paymentKey())) {
            log.warn("결제 승인 응답 비정상(orderId={}, paymentKey 없음)", orderId);
            throw TossPaymentApprovalException.outcomeUnknown(
                    TossPaymentApprovalException.CODE_INVALID_RESPONSE, "결제 승인 결과를 확인할 수 없습니다.");
        }
        if (!STATUS_DONE.equals(response.status())) {
            // 승인 완료가 아닌 상태(대기·부분 취소 등)는 "성공"으로 취급하지 않는다 — 플랜을 먼저 주는
            // 것보다 실패로 확정하고 사용자가 다시 시도하게 하는 편이 안전하다.
            log.warn("결제 승인 미완료 상태(orderId={}, status={})", orderId, response.status());
            throw TossPaymentApprovalException.outcomeUnknown(
                    TossPaymentApprovalException.CODE_INVALID_RESPONSE, "결제가 승인 완료 상태가 아닙니다.");
        }
        return new TossPaymentApproval(
                response.paymentKey(),
                toPaymentMethod(response.method()),
                response.receipt() == null ? null : response.receipt().url(),
                parseApprovedAt(response.approvedAt()));
    }

    /**
     * 토스 인증 헤더 — 시크릿 키 뒤에 콜론을 붙여 Base64 로 인코딩한 HTTP Basic(비밀번호 없는 사용자명).
     * 반환값은 곧 시크릿이므로 절대 로깅하지 않는다.
     */
    private String basicAuthHeader() {
        String credentials = properties.getSecretKey() + ":";
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private PaymentMethod toPaymentMethod(String method) {
        return METHOD_CARD.equals(method) ? PaymentMethod.CARD : null;
    }

    private Instant parseApprovedAt(String approvedAt) {
        if (!StringUtils.hasText(approvedAt)) {
            return null; // 호출부(Payment#markPaid)가 현재 시각으로 대체한다.
        }
        try {
            return OffsetDateTime.parse(approvedAt).toInstant();
        } catch (DateTimeParseException e) {
            // 승인 자체는 성공했으므로 시각 표기 하나 때문에 실패시키지 않는다.
            log.warn("결제 승인 시각 파싱 실패 — 서버 시각으로 대체");
            return null;
        }
    }

    /** PG 오류 바디({@code {"code": "...", "message": "..."}})에서 코드·메시지만 안전하게 뽑는다. */
    private PgError parsePgError(RestClientResponseException e) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString(StandardCharsets.UTF_8));
            String code = body.path("code").asText(null);
            String message = body.path("message").asText(null);
            if (StringUtils.hasText(code)) {
                return new PgError(code, StringUtils.hasText(message) ? message : "결제가 거절되었습니다.");
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // 오류 바디가 JSON 이 아니거나 형식이 다른 경우 — 아래 기본값으로 떨어진다(원문 로깅 금지).
        }
        return new PgError("HTTP_" + e.getStatusCode().value(), "결제가 거절되었습니다.");
    }

    private record PgError(String code, String message) {
    }
}
