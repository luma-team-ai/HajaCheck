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
 * 5경로 커버: 성공(계속)·불일치·폐업·API장애(5xx/타임아웃/연결)·serviceKey 미설정.
 */
class NtsBusinessVerifyClientTest {

    private static final String BASE_URL = "http://nts-test";
    private static final String BRN = "1234567890";
    private static final String REP = "김민수";
    private static final LocalDate START = LocalDate.of(2020, 1, 1);

    private MockRestServiceServer mockServer;
    private RestClient.Builder builder;
    private BizVerifyProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BizVerifyProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setServiceKey("test-service-key");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(5000);
        builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
    }

    private NtsBusinessVerifyClient client() {
        return new NtsBusinessVerifyClient(builder.build(), properties);
    }

    private ResponseActions expectValidate() {
        return mockServer.expect(requestTo(containsString("/api/nts-businessman/v1/validate")))
                .andExpect(method(HttpMethod.POST));
    }

    private ResponseActions expectStatus() {
        return mockServer.expect(requestTo(containsString("/api/nts-businessman/v1/status")))
                .andExpect(method(HttpMethod.POST));
    }

    @Test
    void validate_진위일치_계속사업자_VERIFIED() {
        // 개업일자는 YYYYMMDD 로 직렬화돼야 한다(start_dt).
        mockServer.expect(requestTo(containsString("/api/nts-businessman/v1/validate")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("20200101")))
                .andExpect(content().string(containsString("\"b_no\":\"1234567890\"")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01","b_stt":"계속사업자"}}]}
                                """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.VERIFIED);
        mockServer.verify();
    }

    @Test
    void validate_serviceKey특수문자_percent인코딩되어_전송된다() {
        // data.go.kr "Decoding" 키는 +,/,= 를 포함할 수 있다. 미인코딩 시 서버가 + 를 공백으로 해석해
        // 인증 실패 → 조용한 no-op 가 되므로, 요청 URI 의 serviceKey 는 반드시 percent-encoding 돼야 한다.
        properties.setServiceKey("ab+c/d=e");
        mockServer.expect(requestTo(containsString("serviceKey=ab%2Bc%2Fd%3De")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01"}}]}
                                """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.VERIFIED);
        mockServer.verify();
    }

    @Test
    void validate_진위불일치_MISMATCH() {
        expectValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"02","status":{}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.MISMATCH);
    }

    @Test
    void validate_미등록사업자_valid02_MISMATCH() {
        // 국세청 미등록은 valid=02(불일치)로 응답 → 차단 대상.
        expectValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"02","status":{"b_stt_cd":""}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.MISMATCH);
    }

    @Test
    void validate_폐업사업자_CLOSED() {
        expectValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"03","b_stt":"폐업자"}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.CLOSED);
    }

    @Test
    void validate_휴업사업자_SUSPENDED() {
        expectValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"02","b_stt":"휴업자"}}]}
                        """));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SUSPENDED);
    }

    @Test
    void validate_5xx장애_failopen_SKIPPED() {
        expectValidate().andRespond(withServerError());

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
    }

    @Test
    void validate_읽기타임아웃_failopen_SKIPPED() {
        expectValidate().andRespond(request -> {
            throw new HttpTimeoutException("Response timed out");
        });

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
    }

    @Test
    void validate_연결실패_failopen_SKIPPED() {
        expectValidate().andRespond(request -> {
            throw new ConnectException("Connection refused");
        });

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
    }

    @Test
    void validate_응답형식불량_failopen_SKIPPED() {
        expectValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\":[]}"));

        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
    }

    @Test
    void validate_serviceKey미설정_호출없이_SKIPPED() {
        properties.setServiceKey("");

        // 서버 호출이 없어야 한다(mockServer 에 기대 미등록 → 호출 시 실패).
        assertThat(client().validate(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        mockServer.verify();
    }

    // ---------- verifyRealtime(#648) — 상태조회(status) + validate 조합 ----------

    @Test
    void verifyRealtime_계속사업자_진위일치_VERIFIED() {
        expectStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"01","tax_type":"부가가치세 일반과세자"}]}
                        """));
        expectValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01"}}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.VERIFIED);
        mockServer.verify();
    }

    @Test
    void verifyRealtime_계속사업자_진위불일치_MISMATCH() {
        expectStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"01","tax_type":"부가가치세 일반과세자"}]}
                        """));
        expectValidate().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","valid":"02","status":{"b_stt_cd":"01"}}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.MISMATCH);
        mockServer.verify();
    }

    @Test
    void verifyRealtime_미등록_상태조회만으로_NOT_REGISTERED_validate호출없음() {
        // 상태조회에서 미등록이 확인되면 validate() 를 호출하지 않는다(mockServer 에 validate 기대 미등록
        // → 호출 시 실패하므로, 아래 mockServer.verify() 가 "정확히 상태조회 1회만" 을 보증한다).
        expectStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"","tax_type":"국세청에 등록되지 않은 사업자등록번호입니다."}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.NOT_REGISTERED);
        mockServer.verify();
    }

    @Test
    void verifyRealtime_휴업_SUSPENDED_validate호출없음() {
        expectStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"02","tax_type":"부가가치세 일반과세자"}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SUSPENDED);
        mockServer.verify();
    }

    @Test
    void verifyRealtime_폐업_CLOSED_validate호출없음() {
        expectStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"03","tax_type":"부가가치세 일반과세자"}]}
                        """));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.CLOSED);
        mockServer.verify();
    }

    @Test
    void verifyRealtime_상태조회_5xx장애_failopen_UNAVAILABLE_validate호출없음() {
        expectStatus().andRespond(withServerError());

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        mockServer.verify();
    }

    @Test
    void verifyRealtime_상태조회_타임아웃_failopen_UNAVAILABLE() {
        expectStatus().andRespond(request -> {
            throw new HttpTimeoutException("Response timed out");
        });

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
    }

    @Test
    void verifyRealtime_상태조회_응답형식불량_failopen_UNAVAILABLE() {
        expectStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\":[]}"));

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
    }

    @Test
    void verifyRealtime_계속사업자이나_validate호출실패_failopen_UNAVAILABLE() {
        expectStatus().andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"data":[{"b_no":"1234567890","b_stt_cd":"01","tax_type":"부가가치세 일반과세자"}]}
                        """));
        expectValidate().andRespond(withServerError());

        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        mockServer.verify();
    }

    @Test
    void verifyRealtime_serviceKey미설정_호출없이_UNAVAILABLE() {
        properties.setServiceKey("");

        // 서버 호출이 없어야 한다(mockServer 에 기대 미등록 → 호출 시 실패).
        assertThat(client().verifyRealtime(BRN, REP, START)).isEqualTo(NtsVerificationOutcome.SKIPPED);
        mockServer.verify();
    }
}
