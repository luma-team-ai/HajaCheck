package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiProxyService.generateReport 단위테스트 — RestClient 는 MockRestServiceServer 로 스텁(#239 / HAJA-192).
 * AiProxyServiceTest(defect-explain) 와 동일한 패턴을 report 엔드포인트에 그대로 적용.
 */
class AiProxyServiceReportTest {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/report";

    private MockRestServiceServer mockServer;
    private AiProxyService aiProxyService;

    private static final ReportRequest REQUEST = new ReportRequest(
            new ReportRequest.FacilityInfo("Haja APT", "서울시"),
            List.of(new ReportRequest.ConfirmedDefect("균열", "1동 1층 기둥", "B", "기둥 표면 수평 균열")),
            "regenerate");

    @BeforeEach
    void setUp() {
        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl("http://ai-server-test");
        properties.setInternalKey("test-internal-key");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(60000);

        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        aiProxyService = new AiProxyService(builder.build(), properties);
    }

    @Test
    void generateReport_성공_데이터반환_내부키헤더부착() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", "test-internal-key"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": true,
                                  "data": {
                                    "overview": {"purpose": "목적", "facility_summary": "요약", "scope": "범위"},
                                    "summary": {
                                      "overall_opinion": "양호",
                                      "total_count": 1,
                                      "count_by_grade": {"A": 0, "B": 1, "C": 0, "D": 0, "E": 0},
                                      "key_findings": ["1동 기둥 균열 발생"]
                                    },
                                    "detail": {
                                      "items": [
                                        {"defect_type": "균열", "location": "1동 1층 기둥", "severity_grade": "B",
                                         "description": "기둥 표면 수평 균열", "cause": "건조 수축"}
                                      ]
                                    },
                                    "recommendation": {
                                      "items": [
                                        {"target": "균열", "method": "에폭시 수지 주입", "priority": "중", "legal_basis": "제X조"}
                                      ],
                                      "monitoring_points": ["지하주차장"]
                                    },
                                    "grounding_ok": true
                                  },
                                  "usage": {"tokens": 850}
                                }
                                """));

        ApiResponse<?> response = aiProxyService.generateReport(REQUEST);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        mockServer.verify();
    }

    @Test
    void generateReport_LLM실패_에러코드메시지그대로전파() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"data":null,"usage":null,"error":{"code":"LLM_INVALID_OUTPUT","message":"모델 응답 파싱 실패"}}
                                """));

        ApiResponse<?> response = aiProxyService.generateReport(REQUEST);

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(response.error().message()).isEqualTo("모델 응답 파싱 실패");
    }

    @Test
    void generateReport_연결불가_AI_SERVER_UNREACHABLE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> aiProxyService.generateReport(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE));
    }

    @Test
    void generateReport_5xx응답_AI_INVALID_RESPONSE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aiProxyService.generateReport(REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }
}
