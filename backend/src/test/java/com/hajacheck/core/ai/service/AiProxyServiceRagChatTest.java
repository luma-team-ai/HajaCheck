package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.RagChatRequest;
import com.hajacheck.core.ai.dto.RagChatResponse;
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
 * AiProxyService.ragChat 단위테스트 — RestClient 는 MockRestServiceServer 로 스텁
 * (AiProxyServiceTest(defect-explain)와 동일 패턴, HAJA-32 / #467).
 */
class AiProxyServiceRagChatTest {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/rag-chat";
    private static final Long USER_ID = 1L;

    private static final RagChatRequest REQUEST = new RagChatRequest("균열 보수 기준은 무엇인가요?");

    private MockRestServiceServer mockServer;
    private RestClient.Builder builder;
    private AiServerProperties properties;
    private AiProxyService aiProxyService;

    @BeforeEach
    void setUp() {
        properties = new AiServerProperties();
        properties.setBaseUrl("http://ai-server-test");
        properties.setInternalKey("test-internal-key");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(60000);

        builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        aiProxyService = newService(new InMemoryRateLimiter());
    }

    private AiProxyService newService(RateLimiter rateLimiter) {
        return new AiProxyService(builder.build(), properties, null, new AiProxyRateLimiter(rateLimiter));
    }

    @Test
    void ragChat_성공_요청바디를question으로변환_내부키헤더부착() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", "test-internal-key"))
                // 프론트 요청은 query 필드지만 FastAPI 호출 바디는 question 이어야 한다(필드명 변환 검증).
                .andExpect(content().json("""
                        {"question":"균열 보수 기준은 무엇인가요?"}
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": true,
                                  "data": {
                                    "answer": "균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다.",
                                    "sources": [
                                      {
                                        "doc_id": "42",
                                        "title": "시설물의 안전 및 유지관리에 관한 특별법",
                                        "collection": "regulations",
                                        "locator": "제12조",
                                        "snippet": "관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다.",
                                        "chunk_ref": "42_3"
                                      }
                                    ]
                                  },
                                  "usage": {"tokens": 320}
                                }
                                """));

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, REQUEST);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().answer()).isEqualTo("균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다.");
        assertThat(response.data().sources()).hasSize(1);
        RagChatResponse.SourceCitation source = response.data().sources().get(0);
        assertThat(source.docId()).isEqualTo("42");
        assertThat(source.collection()).isEqualTo("regulations");
        assertThat(source.locator()).isEqualTo("제12조");
        assertThat(source.chunkRef()).isEqualTo("42_3");
        mockServer.verify();
    }

    @Test
    void ragChat_검색결과0건_RAG_NO_RESULT_에러코드메시지그대로전파() {
        // 계약(contract.md): 검색 0건은 success:false + error.code=RAG_NO_RESULT — 예외가 아니라
        // 정상 응답 경로로 그대로 전달돼야 한다(useRagChat.ts가 이를 "근거 없음" 안내로 표시).
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"RAG_NO_RESULT","message":"관련 근거를 찾지 못했습니다"}}
                                """));

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, REQUEST);

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("RAG_NO_RESULT");
        assertThat(response.error().message()).isEqualTo("관련 근거를 찾지 못했습니다");
    }

    @Test
    void ragChat_LLM실패_에러코드메시지그대로전파() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"error":{"code":"LLM_INVALID_OUTPUT","message":"모델 응답 파싱 실패"}}
                                """));

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, REQUEST);

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(response.error().message()).isEqualTo("모델 응답 파싱 실패");
    }

    @Test
    void ragChat_연결불가_AI_SERVER_UNREACHABLE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE));
    }

    @Test
    void ragChat_5xx응답_AI_SERVER_ERROR예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_ERROR));
    }

    @Test
    void ragChat_4xx응답_AI_REQUEST_REJECTED예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"invalid request\"}"));

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
    }

    @Test
    void ragChat_전역rate_limit초과_AUTH_TOO_MANY_REQUESTS_내부호출없음() {
        AiProxyService limited = newService((key, limit, window) -> !key.startsWith("rate:ai-proxy:global")
                && !key.equals("rate:ai-proxy:daily"));

        assertThatThrownBy(() -> limited.ragChat(USER_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS));
        mockServer.verify(); // 기대치 없음 = 어떤 FastAPI 요청도 발생하지 않아야 통과
    }

    @Test
    void ragChat_사용자rate_limit초과_AUTH_TOO_MANY_REQUESTS_내부호출없음() {
        AiProxyService limited = newService((key, limit, window) -> !key.startsWith("rate:ai-proxy:user:"));

        assertThatThrownBy(() -> limited.ragChat(USER_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS));
        mockServer.verify();
    }

    @Test
    void ragChat_응답형식불량_AI_INVALID_RESPONSE예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"foo\":\"bar\"}"));

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }
}
