package com.hajacheck.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.entity.PaymentMethod;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * TossPaymentsClient 단위테스트 — RestClient 는 MockRestServiceServer 로 스텁(#988 / HAJA-489).
 * {@code NtsBusinessVerifyClientTest} 와 동일한 골격이되, 이 클라이언트는 <b>fail-close</b>라 모든 실패가
 * 예외로 표면화되는지를 본다(진위확인처럼 SKIPPED 로 삼키지 않는다).
 */
class TossPaymentsClientTest {

    private static final String BASE_URL = "http://toss-test";
    private static final String SECRET_KEY = "test_sk_dummy_for_unit_test";
    private static final String PAYMENT_KEY = "test_payment_key_abc";
    private static final String ORDER_ID = "haja-00000000-0000-0000-0000-000000000001";
    private static final long AMOUNT = 99000L;

    private MockRestServiceServer mockServer;
    private RestClient.Builder builder;
    private TossPaymentsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TossPaymentsProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setSecretKey(SECRET_KEY);
        builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
    }

    private TossPaymentsClient client() {
        return new TossPaymentsClient(builder.build(), properties, new ObjectMapper());
    }

    @Test
    void 승인성공이면_저장대상값만_담긴결과를_반환한다() {
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andExpect(method(HttpMethod.POST))
                // 시크릿 키 + ":" 를 Base64 로 인코딩한 HTTP Basic(비밀번호 없는 사용자명) — 토스 규격.
                .andExpect(header("Authorization", expectedAuth))
                // 승인 바디에는 서버가 확정한 금액이 그대로 실린다.
                .andExpect(content().string(containsString("\"amount\":99000")))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "test_payment_key_abc",
                          "orderId": "haja-00000000-0000-0000-0000-000000000001",
                          "status": "DONE",
                          "method": "카드",
                          "approvedAt": "2026-07-27T10:00:00+09:00",
                          "receipt": {"url": "https://receipt.example/abc"},
                          "card": {"number": "12345678****123*"}
                        }
                        """, MediaType.APPLICATION_JSON));

        TossPaymentApproval approval = client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(approval.paymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(approval.method()).isEqualTo(PaymentMethod.CARD);
        assertThat(approval.receiptUrl()).isEqualTo("https://receipt.example/abc");
        assertThat(approval.approvedAt()).isEqualTo(Instant.parse("2026-07-27T01:00:00Z"));
        mockServer.verify();
    }

    @Test
    void 매핑밖_결제수단은_null로_남기고_승인은_성공시킨다() {
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withSuccess("""
                        {"paymentKey": "test_payment_key_abc", "status": "DONE", "method": "간편결제",
                         "approvedAt": "2026-07-27T10:00:00+09:00"}
                        """, MediaType.APPLICATION_JSON));

        TossPaymentApproval approval = client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);

        // 수단 표기 하나 때문에 이미 승인된 결제를 실패로 만들지 않는다.
        assertThat(approval.method()).isNull();
        assertThat(approval.receiptUrl()).isNull();
    }

    @Test
    void PG가_4xx로_거절하면_코드와_사유를_담아_예외로_표면화한다() {
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code": "REJECT_CARD_COMPANY", "message": "카드사 승인 거절"}
                                """));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> {
                    TossPaymentApprovalException ex = (TossPaymentApprovalException) e;
                    assertThat(ex.getCode()).isEqualTo("REJECT_CARD_COMPANY");
                    assertThat(ex.getSafeMessage()).isEqualTo("카드사 승인 거절");
                    // 예외 메시지에는 paymentKey·시크릿이 실리지 않는다(스택 로깅 대비).
                    assertThat(ex.getMessage()).doesNotContain(PAYMENT_KEY).doesNotContain(SECRET_KEY);
                });
    }

    @Test
    void PG_5xx는_삼키지않되_확정거절이_아니라_결과불명이다() {
        // ⚠️ 리뷰 P1 — 예전 구현은 RestClientResponseException 이면 상태코드와 무관하게 rejected 로 보내
        // 주문을 FAILED 로 닫았다. 5xx 는 "PG 가 거절했다"가 아니라 "결과를 알 수 없다"이며, 특히 504 는
        // 타임아웃과 의미가 같은데 예외 타입만 다르다는 이유로 정반대 처리를 받았다.
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withServerError());

        // 진위확인(fail-open)과 달리 결제는 "장애니까 통과"가 없다 — 승인 불명이면 플랜을 주지 않는다.
        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> {
                    TossPaymentApprovalException ex = (TossPaymentApprovalException) e;
                    assertThat(ex.isOutcomeUnknown()).isTrue();
                    assertThat(ex.isAlreadyProcessed()).isFalse();
                });
    }

    @Test
    void PG_504는_타임아웃과_같은_결과불명으로_분류한다() {
        // 토스의 FAILED_INTERNAL_SYSTEM_PROCESSING(재시도 안내 코드)이 이 경로로 온다.
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"FAILED_INTERNAL_SYSTEM_PROCESSING\", \"message\": \"일시적인 오류입니다.\"}"));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> {
                    TossPaymentApprovalException ex = (TossPaymentApprovalException) e;
                    assertThat(ex.isOutcomeUnknown()).isTrue();
                    assertThat(ex.getCode()).isEqualTo("FAILED_INTERNAL_SYSTEM_PROCESSING");
                });
    }

    @Test
    void PG_429_스로틀도_결과불명이다() {
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"TOO_MANY_REQUESTS\", \"message\": \"잠시 후 다시 시도해 주세요.\"}"));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> assertThat(((TossPaymentApprovalException) e).isOutcomeUnknown()).isTrue());
    }

    @Test
    void 오류바디가_JSON이_아닌_5xx도_확정거절이_아니다() {
        // 폴백 코드(HTTP_5xx)는 PG 가 실패라고 말한 적조차 없는 경우라 확정 거절로 다룰 근거가 없다.
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway down</html>"));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> {
                    TossPaymentApprovalException ex = (TossPaymentApprovalException) e;
                    assertThat(ex.isOutcomeUnknown()).isTrue();
                    assertThat(ex.getCode()).isEqualTo("HTTP_502");
                });
    }

    @Test
    void PG_4xx_확정거절은_그대로_rejected다() {
        // 5xx 를 불명으로 넓힌 뒤에도 4xx 는 확정 거절로 남아야 한다(과분류가 반대로 번지지 않았는지).
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"REJECT_CARD_COMPANY\", \"message\": \"카드사 승인 거절\"}"));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> assertThat(((TossPaymentApprovalException) e).isOutcomeUnknown()).isFalse());
    }

    @Test
    void 이미처리된결제_4xx는_확정거절이면서_alreadyProcessed로_표시된다() {
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"ALREADY_PROCESSED_PAYMENT\", \"message\": \"이미 처리된 결제입니다.\"}"));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> {
                    TossPaymentApprovalException ex = (TossPaymentApprovalException) e;
                    assertThat(ex.isAlreadyProcessed()).isTrue();
                    assertThat(ex.isOutcomeUnknown()).isFalse();
                });
    }

    @Test
    void 연결실패_타임아웃은_재시도없이_UNREACHABLE로_확정한다() {
        // 승인은 멱등이 아닌 쓰기라 재시도하면 중복 청구 위험이 있다 — 1회만 호출하고 실패로 확정한다.
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(request -> {
                    throw new ResourceAccessException("connect fail", new ConnectException("refused"));
                });

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> assertThat(((TossPaymentApprovalException) e).getCode())
                        .isEqualTo(TossPaymentApprovalException.CODE_UNREACHABLE));
        mockServer.verify(); // 기대 호출 1건만 소비 = 재시도 없음
    }

    @Test
    void 승인완료상태가_아니면_성공으로_취급하지않는다() {
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withSuccess("""
                        {"paymentKey": "test_payment_key_abc", "status": "WAITING_FOR_DEPOSIT"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> assertThat(((TossPaymentApprovalException) e).getCode())
                        .isEqualTo(TossPaymentApprovalException.CODE_INVALID_RESPONSE));
    }

    @Test
    void 오류바디가_JSON이_아니면_원문을_노출하지않고_HTTP코드로_대체한다() {
        mockServer.expect(requestTo(containsString("/v1/payments/confirm")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway down: secret=test_sk_dummy_for_unit_test</html>"));

        assertThatThrownBy(() -> client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(TossPaymentApprovalException.class)
                .satisfies(e -> {
                    TossPaymentApprovalException ex = (TossPaymentApprovalException) e;
                    assertThat(ex.getCode()).isEqualTo("HTTP_502");
                    // DB(failure_message)에 저장되는 값이라 응답 원문이 새어 들어가면 안 된다.
                    assertThat(ex.getSafeMessage()).doesNotContain(SECRET_KEY);
                });
    }

    @Test
    void 시크릿_미설정이면_isConfigured가_false다() {
        properties.setSecretKey("");
        assertThat(client().isConfigured()).isFalse();
        properties.setSecretKey(SECRET_KEY);
        assertThat(client().isConfigured()).isTrue();
    }

    @Test
    void 승인요청_URL에는_시크릿이_실리지않는다() {
        // 국세청 클라이언트와 달리 인증을 쿼리 파라미터가 아닌 Authorization 헤더로 보낸다 —
        // URL 은 접근 로그·프록시 로그에 그대로 남기 때문이다.
        mockServer.expect(requestTo(not(containsString(SECRET_KEY))))
                .andRespond(withSuccess("""
                        {"paymentKey": "test_payment_key_abc", "status": "DONE", "method": "카드"}
                        """, MediaType.APPLICATION_JSON));

        client().confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);
        mockServer.verify();
    }
}
