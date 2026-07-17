package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.DefectExplainRequest;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiProxyService 단위테스트 — RestClient 는 MockRestServiceServer 로 스텁(#228 handoff).
 */
class AiProxyServiceTest {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/defect-explain";

    private MockRestServiceServer mockServer;
    private AiProxyService aiProxyService;

    private static final DefectExplainRequest REQUEST =
            new DefectExplainRequest("균열", "C", "1층 기둥", "공동주택");

    @BeforeEach
    void setUp() {
        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl("http://ai-server-test");
        properties.setInternalKey("test-internal-key");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(60000);

        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        // briefingStatsService 는 defect-explain 테스트에서 사용하지 않아 null(#248 추가 의존성).
        aiProxyService = new AiProxyService(builder.build(), properties, null);
    }

    @Test
    void explainDefect_성공_데이터반환_내부키헤더부착() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", "test-internal-key"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"cause":"철근 부식","risk":"구조 내력 저하","action":"단면 보수 후 재도장"},"usage":{"tokens":120}}
                                """));

        ApiResponse<?> response = aiProxyService.explainDefect(REQUEST);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        mockServer.verify();
    }

    @Test
    void explainDefect_LLM실패_에러코드메시지그대로전파() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"LLM_INVALID_OUTPUT","message":"모델 응답 파싱 실패"}}
                                """));

        ApiResponse<?> response = aiProxyService.explainDefect(REQUEST);

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(response.error().message()).isEqualTo("모델 응답 파싱 실패");
    }

    @Test
    void explainDefect_연결불가_AI_SERVER_UNREACHABLE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> aiProxyService.explainDefect(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE));
    }

    @Test
    void explainDefect_읽기타임아웃_HttpTimeoutException_AI_SERVER_TIMEOUT예외() {
        // 이 레포는 httpclient5/okhttp 미의존이라 RestClient 가 JdkClientHttpRequestFactory 를 쓰고,
        // 실제 read 타임아웃은 java.net.http.HttpTimeoutException 으로 던져진다(SocketTimeoutException 아님).
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new HttpTimeoutException("Response timed out");
                });

        assertThatThrownBy(() -> aiProxyService.explainDefect(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_TIMEOUT));
    }

    @Test
    void explainDefect_연결타임아웃_HttpConnectTimeoutException_AI_SERVER_UNREACHABLE예외() {
        // HttpConnectTimeoutException 은 HttpTimeoutException 의 하위형이지만 연결 자체 실패로 보아
        // UNREACHABLE 로 분류해야 한다(TIMEOUT 아님) — mapConnectionFailure 분기 순서 검증.
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new HttpConnectTimeoutException("Connect timed out");
                });

        assertThatThrownBy(() -> aiProxyService.explainDefect(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE));
    }

    @Test
    void explainDefect_5xx응답_AI_SERVER_ERROR예외() {
        // #334 P3: RestClientResponseException 의 상태코드로 4xx/5xx 를 구분 — 5xx 는 업스트림 장애로 AI_SERVER_ERROR.
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aiProxyService.explainDefect(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_ERROR));
    }

    @Test
    void explainDefect_4xx응답_AI_REQUEST_REJECTED예외() {
        // #334 P3: 4xx 는 우리 쪽 요청이 AI 서버 계약에 안 맞아 거부된 것으로 보아 AI_REQUEST_REJECTED.
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"invalid request\"}"));

        assertThatThrownBy(() -> aiProxyService.explainDefect(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
    }

    @Test
    void explainDefect_응답형식불량_AI_INVALID_RESPONSE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"foo\":\"bar\"}"));

        // success 필드 없이 역직렬화되면 boolean 기본값 false 로 매핑되고 error 도 없어 AI_INVALID_RESPONSE.
        assertThatThrownBy(() -> aiProxyService.explainDefect(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }
}
