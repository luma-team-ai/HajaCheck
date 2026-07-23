package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.BriefingResponse;
import com.hajacheck.core.ai.dto.BriefingStatsRequest;
import com.hajacheck.core.ai.support.AiProxyRateLimiter;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.InMemoryRateLimiter;
import java.net.ConnectException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiProxyService.briefing 단위테스트 — RestClient 는 MockRestServiceServer 로 스텁, DashboardStats
 * 조립은 BriefingStatsService 를 Mockito 로 스텁한다(#248 / HAJA-197).
 * AiProxyServiceTest(defect-explain)/AiProxyServiceReportTest 와 동일 패턴을 briefing 엔드포인트에 적용.
 */
@ExtendWith(MockitoExtension.class)
class AiProxyServiceBriefingTest {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/briefing";
    private static final Long OWNER_ID = 1L;

    private static final BriefingStatsRequest STATS = new BriefingStatsRequest(
            3, 4, 2, 1, 8, 10, "균열", 1, Map.of("A", 3L, "B", 4L, "C", 1L, "D", 0L, "E", 1L));

    @Mock
    private BriefingStatsService briefingStatsService;

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
        // briefing 의 rate-limit 은 BriefingStatsService.buildStats(여기선 @Mock)에 있어 이 경로엔
        // 직접 개입하지 않지만, 생성자 의존성이라 in-memory fake 를 주입한다.
        aiProxyService = new AiProxyService(builder.build(), properties, briefingStatsService,
                new AiProxyRateLimiter(new InMemoryRateLimiter()));
    }

    @Test
    void briefing_성공_데이터반환_내부키헤더부착() {
        when(briefingStatsService.buildStats(OWNER_ID)).thenReturn(STATS);
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", "test-internal-key"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": true,
                                  "data": {
                                    "briefing": "이번 주 하자 발생이 감소했습니다.",
                                    "recommendation": "균열 유형 점검을 우선하세요.",
                                    "facts": {
                                      "this_week_defects": 8,
                                      "last_week_defects": 10,
                                      "change_pct": -20,
                                      "trend": "감소",
                                      "top_defect_type": "균열",
                                      "critical_defects": 1
                                    }
                                  },
                                  "usage": {"tokens": 210}
                                }
                                """));

        ApiResponse<BriefingResponse> response = aiProxyService.briefing(OWNER_ID);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().briefing()).isEqualTo("이번 주 하자 발생이 감소했습니다.");
        assertThat(response.data().facts().thisWeekDefects()).isEqualTo(8L);
        assertThat(response.data().facts().trend()).isEqualTo("감소");
        mockServer.verify();
    }

    @Test
    void briefing_LLM실패_에러코드메시지그대로전파() {
        when(briefingStatsService.buildStats(OWNER_ID)).thenReturn(STATS);
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"LLM_INVALID_OUTPUT","message":"모델 응답 파싱 실패"}}
                                """));

        ApiResponse<BriefingResponse> response = aiProxyService.briefing(OWNER_ID);

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(response.error().message()).isEqualTo("모델 응답 파싱 실패");
    }

    @Test
    void briefing_연결불가_AI_SERVER_UNREACHABLE예외() {
        when(briefingStatsService.buildStats(OWNER_ID)).thenReturn(STATS);
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> aiProxyService.briefing(OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE));
    }

    @Test
    void briefing_5xx응답_AI_SERVER_ERROR예외() {
        // #334 P3: 5xx 는 업스트림(AI 서버) 자체 장애로 보아 AI_SERVER_ERROR 로 분기.
        when(briefingStatsService.buildStats(OWNER_ID)).thenReturn(STATS);
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aiProxyService.briefing(OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_ERROR));
    }

    @Test
    void briefing_4xx응답_AI_REQUEST_REJECTED예외() {
        // #334 P3: 4xx 는 요청 데이터가 AI 서버 계약에 안 맞아 거부된 것으로 보아 AI_REQUEST_REJECTED 로 분기.
        when(briefingStatsService.buildStats(OWNER_ID)).thenReturn(STATS);
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"invalid request\"}"));

        assertThatThrownBy(() -> aiProxyService.briefing(OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
    }
}
