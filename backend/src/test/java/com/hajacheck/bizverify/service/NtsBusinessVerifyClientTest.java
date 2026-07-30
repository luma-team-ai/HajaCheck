package com.hajacheck.bizverify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.bizverify.config.BizVerifyProperties;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/**
 * NtsBusinessVerifyClient 단위테스트 — RestClient 는 MockRestServiceServer 로 스텁(#596).
 * PR #889 P1(#880) 이후 제출 경로({@code validate()})와 실시간 경로({@code verifyRealtime()})가 서로 다른
 * RestClient 빈(재시도 여부·read-timeout 상이)을 쓰므로, 이 테스트도 경로별로 별도의 MockRestServiceServer
 * ({@link #submitMockServer}/{@link #realtimeMockServer})를 바인딩해 검증한다.
 *
 * <p>커버 경로: 성공(계속)·불일치·폐업·API장애(5xx/타임아웃/연결)·serviceKey 미설정, 제출 경로 재시도
 * 없음(1회 호출) vs 실시간 경로 재시도(#880), 재시도 하한 방어(P2).
 */
class NtsBusinessVerifyClientTest {

    private static final String BASE_URL = "http://nts-test";
    private static final String BRN = "1234567890";
    private static final String REP = "김민수";
    private static final LocalDate START = LocalDate.of(2020, 1, 1);

    private MockRestServiceServer submitMockServer;
    private MockRestServiceServer realtimeMockServer;
    private RestClient.Builder submitBuilder;
    private RestClient.Builder realtimeBuilder;
    private BizVerifyProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BizVerifyProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setServiceKey("test-service-key");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(5000);
        properties.setRealtimeReadTimeoutMs(8000);
        // 재시도 자체는 그대로 두되(기본 1회, 실시간 경로에만 적용) 테스트 속도를 위해 백오프는 0으로 낮춘다.
        properties.setRetryMaxAttempts(1);
        properties.setRetryBackoffMs(0);

        submitBuilder = RestClient.builder().baseUrl(BASE_URL);
        submitMockServer = MockRestServiceServer.bindTo(submitBuilder).build();
        realtimeBuilder = RestClient.builder().baseUrl(BASE_URL);
        realtimeMockServer = MockRestServiceServer.bindTo(realtimeBuilder).build();
    }

    private NtsBusinessVerifyClient client() {
        return new NtsBusinessVerifyClient(submitBuilder.build(), realtimeBuilder.build(), properties);
    }

    private ResponseActions expectSubmitValidate() {
        return submitMockServer.expect(requestTo(containsString("/api/nts-businessman/v1/validate")))
                .andExpect(method(HttpMethod.POST));
    }

    private ResponseActions expectRealtimeStatus() {
        return realtimeMockServer.expect(requestTo(containsString("/api/nts-businessman/v1/status")))
                .andExpect(method(HttpMethod.POST));
    }

    private ResponseActions expectRealtimeValidate() {
        return realtimeMockServer.expect(requestTo(containsString("/api/nts-businessman/v1/validate")))
                .andExpect(method(HttpMethod.POST));
    }

    // ---------- validate(#596) — 회원가입 제출 경로 전용, 재시도 없음(PR #889 P1) ----------

    @Test
    void validate_진위일치_계속사업자_VERIFIED() {
        // 개업일자는 YYYYMMDD 로 직렬화돼야 한다(start_dt).
        submitMockServer.expect(requestTo(containsString("/api/nts-businessman/v1/validate")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("20200101")))
                .andExpect(content().string(containsString("\"b_no\":\"1234567890\"")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01","b_stt":"계속사업자"}}]}
                                """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.VERIFIED);
        submitMockServer.verify();
    }

    @Test
    void validate_serviceKey특수문자_percent인코딩되어_전송된다() {
        // data.go.kr "Decoding" 키는 +,/,= 를 포함할 수 있다. 미인코딩 시 서버가 + 를 공백으로 해석해
        // 인증 실패 → 조용한 no-op 가 되므로, 요청 URI 의 serviceKey 는 반드시 percent-encoding 돼야 한다.
        properties.setServiceKey("ab+c/d=e");
        submitMockServer.expect(requestTo(containsString("serviceKey=ab%2Bc%2Fd%3De")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01"}}]}
                                """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.VERIFIED);
        submitMockServer.verify();
    }

    @Test
    void validate_진위불일치_MISMATCH() {
        expectSubmitValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"02","status":{}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.MISMATCH);
    }

    @Test
    void validate_미등록사업자_valid02_MISMATCH() {
        // 국세청 미등록은 valid=02(불일치)로 응답 → 차단 대상.
        expectSubmitValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"02","status":{"b_stt_cd":""}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.MISMATCH);
    }

    @Test
    void validate_폐업사업자_CLOSED() {
        expectSubmitValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"03","b_stt":"폐업자"}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.CLOSED);
    }

    @Test
    void validate_휴업사업자_SUSPENDED() {
        expectSubmitValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"02","b_stt":"휴업자"}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SUSPENDED);
    }

    @Test
    void validate_5xx장애도_재시도없이_1회호출로_failopen_SKIPPED() {
        // PR #889 P1: 제출 경로(무인증+rate-limit 없음)는 재시도하지 않는다 — mockServer 에 1개 기대만
        // 등록하고 verify() 로 정확히 1회 호출됐음을 보증한다(2회째 호출 시 "예상 밖 요청"으로 실패).
        expectSubmitValidate().andRespond(withServerError());

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        submitMockServer.verify();
    }

    @Test
    void validate_읽기타임아웃도_재시도없이_1회호출로_failopen_SKIPPED() {
        expectSubmitValidate().andRespond(request -> {
            throw new HttpTimeoutException("Response timed out");
        });

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        submitMockServer.verify();
    }

    @Test
    void validate_연결실패도_재시도없이_1회호출로_failopen_SKIPPED() {
        expectSubmitValidate().andRespond(request -> {
            throw new ConnectException("Connection refused");
        });

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        submitMockServer.verify();
    }

    @Test
    void validate_4xx는_1회호출로_failopen_SKIPPED() {
        expectSubmitValidate().andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":-4,\"msg\":\"HTTP ERROR 400\"}"));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        submitMockServer.verify();
    }

    @Test
    void validate_응답파싱실패는_1회호출로_failopen_SKIPPED() {
        // 정상 HTTP 200이나 역직렬화 자체가 실패하는 형식 불량(malformed JSON).
        expectSubmitValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("not-a-json"));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        submitMockServer.verify();
    }

    @Test
    void validate_응답형식불량_해석불가는_1회호출로_failopen_SKIPPED() {
        // data 빈 배열 — 정상 파싱되지만 해석 불가(interpret 단계).
        expectSubmitValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\":[]}"));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        submitMockServer.verify();
    }

    @Test
    void validate_serviceKey미설정_호출없이_SKIPPED() {
        properties.setServiceKey("");

        // 서버 호출이 없어야 한다(mockServer 에 기대 미등록 → 호출 시 실패).
        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        submitMockServer.verify();
    }

    // ---------- verifyRealtime(#648) — 상태조회(status) + validate 조합, 재시도 적용(#880) ----------

    @Test
    void verifyRealtime_계속사업자_진위일치_VERIFIED() {
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"01","tax_type":"부가가치세 일반과세자"}]}
                        """));
        expectRealtimeValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01"}}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.VERIFIED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_계속사업자_진위불일치_MISMATCH() {
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"01","tax_type":"부가가치세 일반과세자"}]}
                        """));
        expectRealtimeValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"02","status":{"b_stt_cd":"01"}}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.MISMATCH);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_미등록_상태조회만으로_NOT_REGISTERED_validate호출없음() {
        // 상태조회에서 미등록이 확인되면 validate 를 호출하지 않는다(realtimeMockServer 에 validate 기대
        // 미등록 → 호출 시 실패하므로, 아래 verify() 가 "정확히 상태조회 1회만" 을 보증한다).
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"","tax_type":"국세청에 등록되지 않은 사업자등록번호입니다."}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.NOT_REGISTERED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_휴업_SUSPENDED_validate호출없음() {
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"02","tax_type":"부가가치세 일반과세자"}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SUSPENDED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_폐업_CLOSED_validate호출없음() {
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"03","tax_type":"부가가치세 일반과세자"}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.CLOSED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_상태조회_5xx장애_재시도소진_failopen_UNAVAILABLE_validate호출없음() {
        // retryMaxAttempts=1 → 상태조회 2회 시도 모두 5xx면 재시도 소진 후 SKIPPED, validate 는 호출 안됨.
        expectRealtimeStatus().andRespond(withServerError());
        expectRealtimeStatus().andRespond(withServerError());

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_상태조회_5xx장애_재시도후_2차성공시_계속진행() {
        // 상태조회 1차 5xx → 재시도 → 2차 성공(계속사업자) → validateForRealtime 정상 호출.
        expectRealtimeStatus().andRespond(withServerError());
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"01","tax_type":"부가가치세 일반과세자"}]}
                        """));
        expectRealtimeValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01"}}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.VERIFIED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_상태조회_타임아웃_재시도소진_failopen_UNAVAILABLE() {
        expectRealtimeStatus().andRespond(request -> {
            throw new HttpTimeoutException("Response timed out");
        });
        expectRealtimeStatus().andRespond(request -> {
            throw new HttpTimeoutException("Response timed out");
        });

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_상태조회_4xx는_재시도하지않고_1회호출로_failopen_UNAVAILABLE() {
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":-4,\"msg\":\"HTTP ERROR 400\"}"));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_상태조회_응답형식불량_failopen_UNAVAILABLE() {
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\":[]}"));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
    }

    @Test
    void verifyRealtime_계속사업자이나_validate호출실패_재시도소진_failopen_UNAVAILABLE() {
        expectRealtimeStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"01","tax_type":"부가가치세 일반과세자"}]}
                        """));
        expectRealtimeValidate().andRespond(withServerError());
        expectRealtimeValidate().andRespond(withServerError());

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        realtimeMockServer.verify();
    }

    @Test
    void verifyRealtime_serviceKey미설정_호출없이_UNAVAILABLE() {
        properties.setServiceKey("");

        // 서버 호출이 없어야 한다(realtimeMockServer 에 기대 미등록 → 호출 시 실패).
        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        realtimeMockServer.verify();
    }

    // ---------- P2(PR #889) — retry-max-attempts 음수 설정 방어(Math.max(1, ...) 하한) ----------

    @Test
    void verifyRealtime_상태조회_retryMaxAttempts음수설정_최소1회는_시도되고_failopen_SKIPPED() {
        // retryMaxAttempts=-5 처럼 잘못 설정돼도 executeWithRetry 의 Math.max(1, ...) 하한 덕에 최소 1회는
        // 시도되고, 예외가 fail-open catch 블록을 우회해 500 으로 새지 않는다 — 5xx 1건만 등록해 정확히
        // 1회 호출됐는지(추가 재시도 없이) 검증한다.
        properties.setRetryMaxAttempts(-5);
        expectRealtimeStatus().andRespond(withServerError());

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        realtimeMockServer.verify();
    }
}
