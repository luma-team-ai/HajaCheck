package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.support.AiProxyRateLimiter;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.InMemoryRateLimiter;
import java.net.ConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiProxyService.ocrBusinessLicense 단위테스트(#557 / HAJA-324) — RestClient 는 MockRestServiceServer 로
 * 스텁(AiProxyServiceTest/AiProxyServiceBriefingTest 와 동일 패턴).
 */
class AiProxyServiceBusinessLicenseOcrTest {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/business-license-ocr";

    private MockRestServiceServer mockServer;
    private AiProxyService aiProxyService;

    @BeforeEach
    void setUp() {
        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl("http://ai-server-test");
        properties.setInternalKey("test-internal-key");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(60000);

        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        // briefingStatsService 는 OCR 프록시 테스트에서 사용하지 않아 null(AiProxyServiceTest 와 동일).
        // OCR 프록시는 AiProxyRateLimiter 대상이 아니지만(자체 rate-limit 은 BusinessLicenseOcrService),
        // 생성자 의존성이라 in-memory fake 를 주입한다.
        aiProxyService = new AiProxyService(builder.build(), properties, null,
                new AiProxyRateLimiter(new InMemoryRateLimiter()));
    }

    @Test
    void ocrBusinessLicense_성공_데이터반환_내부키헤더부착_image_base64로전송() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", "test-internal-key"))
                .andExpect(content().json("{\"image_base64\":\"ZmFrZS1pbWFnZS1ieXRlcw==\"}"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": true,
                                  "data": {
                                    "businessRegistrationNumber": "123-45-67890",
                                    "companyName": "하자체크",
                                    "representativeName": "김대표",
                                    "businessStartDate": "2020-01-15",
                                    "raw": {"lineCount": 12, "avgConfidence": 0.93},
                                    "stub": false
                                  }
                                }
                                """));

        ApiResponse<BusinessLicenseOcrResponse> response =
                aiProxyService.ocrBusinessLicense("ZmFrZS1pbWFnZS1ieXRlcw==");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().businessRegistrationNumber()).isEqualTo("123-45-67890");
        assertThat(response.data().companyName()).isEqualTo("하자체크");
        assertThat(response.data().representativeName()).isEqualTo("김대표");
        assertThat(response.data().businessStartDate()).isEqualTo("2020-01-15");
        mockServer.verify();
    }

    @Test
    void ocrBusinessLicense_개업일자_인식실패시_null_그대로전달() {
        // AI 서버가 businessStartDate 를 못 뽑으면 null 로 내려온다(#598) — 프록시는 별도 가공 없이
        // 그대로 통과시켜야 하고(허위 값을 만들어내지 않음), FE가 이를 보고 수동 입력으로 폴백한다.
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": true,
                                  "data": {
                                    "businessRegistrationNumber": "123-45-67890",
                                    "companyName": "하자체크",
                                    "representativeName": "김대표",
                                    "businessStartDate": null,
                                    "raw": {"lineCount": 12, "avgConfidence": 0.93},
                                    "stub": false
                                  }
                                }
                                """));

        ApiResponse<BusinessLicenseOcrResponse> response =
                aiProxyService.ocrBusinessLicense("ZmFrZS1pbWFnZS1ieXRlcw==");

        assertThat(response.success()).isTrue();
        assertThat(response.data().businessStartDate()).isNull();
    }

    @Test
    void ocrBusinessLicense_OCR실패_에러코드메시지그대로전파() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"LLM_INVALID_OUTPUT","message":"사업자등록증 인식 중 오류가 발생했습니다"}}
                                """));

        ApiResponse<BusinessLicenseOcrResponse> response = aiProxyService.ocrBusinessLicense("YW55LWJhc2U2NA==");

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(response.error().message()).isEqualTo("사업자등록증 인식 중 오류가 발생했습니다");
    }

    @Test
    void ocrBusinessLicense_연결불가_AI_SERVER_UNREACHABLE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> aiProxyService.ocrBusinessLicense("YW55LWJhc2U2NA=="))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE));
    }

    @Test
    void ocrBusinessLicense_5xx응답_AI_SERVER_ERROR예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aiProxyService.ocrBusinessLicense("YW55LWJhc2U2NA=="))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_ERROR));
    }

    @Test
    void ocrBusinessLicense_응답형식불량_AI_INVALID_RESPONSE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"foo\":\"bar\"}"));

        assertThatThrownBy(() -> aiProxyService.ocrBusinessLicense("YW55LWJhc2U2NA=="))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }
}
