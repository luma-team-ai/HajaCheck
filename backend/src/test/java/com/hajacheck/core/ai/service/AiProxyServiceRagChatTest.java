package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.service.ChatSessionService;
import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.RagChatRequest;
import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.ai.support.AiProxyRateLimiter;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.StubRateLimiter;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiProxyService.ragChat 단위테스트 — RestClient 는 MockRestServiceServer 로 스텁
 * (AiProxyServiceTest(defect-explain)와 동일 패턴, HAJA-32 / #467).
 */
class AiProxyServiceRagChatTest {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/rag-chat";
    private static final Long USER_ID = 1L;
    private static final Long COMPANY_ID = 7L;
    private static final Long SESSION_ID = 100L;
    private static final Long OTHER_USER_SESSION_ID = 999L;

    private static final RagChatRequest REQUEST = new RagChatRequest("균열 보수 기준은 무엇인가요?");

    private MockRestServiceServer mockServer;
    private RestClient.Builder builder;
    private AiServerProperties properties;
    private AiProxyService aiProxyService;
    private ChatSessionService chatSessionService;
    private ChatMessageRepository chatMessageRepository;
    private RagConversationPersistenceService ragConversationPersistenceService;

    @BeforeEach
    void setUp() {
        chatSessionService = mock(ChatSessionService.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        ragConversationPersistenceService = mock(RagConversationPersistenceService.class);
        when(chatMessageRepository.findTop6BySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of());
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
        return new AiProxyService(builder.build(), properties, null, new AiProxyRateLimiter(rateLimiter),
                builder.build(), chatSessionService, chatMessageRepository, ragConversationPersistenceService);
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

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST);

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

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST);

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

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST);

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

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE));
    }

    @Test
    void ragChat_5xx응답_AI_SERVER_ERROR예외() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST))
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

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
    }

    @Test
    void ragChat_전역rate_limit초과_AUTH_TOO_MANY_REQUESTS_내부호출없음() {
        AiProxyService limited = newService(StubRateLimiter.of((key, limit, window) -> !key.startsWith("rate:ai-proxy:global")
                && !key.equals("rate:ai-proxy:daily")));

        assertThatThrownBy(() -> limited.ragChat(USER_ID, COMPANY_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS));
        mockServer.verify(); // 기대치 없음 = 어떤 FastAPI 요청도 발생하지 않아야 통과
    }

    @Test
    void ragChat_사용자rate_limit초과_AUTH_TOO_MANY_REQUESTS_내부호출없음() {
        AiProxyService limited = newService(StubRateLimiter.of((key, limit, window) -> !key.startsWith("rate:ai-proxy:user:")));

        assertThatThrownBy(() -> limited.ragChat(USER_ID, COMPANY_ID, REQUEST))
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

        assertThatThrownBy(() -> aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }

    // ---- 세션 소유 검증(#1467/HAJA-647) ----

    @Test
    void ragChat_sessionId없음_세션검증생략_기존단발질의그대로() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"답변","sources":[]}}
                                """));

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST);

        assertThat(response.success()).isTrue();
        // sessionId 가 없으면 세션 조회 자체를 하지 않는다(기존 호출 경로 회귀 없음).
        verifyNoInteractions(chatSessionService);
        mockServer.verify();
    }

    @Test
    void ragChat_sessionId있음_소유자일치_검증통과후AI서버호출() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"답변","sources":[]}}
                                """));

        ApiResponse<RagChatResponse> response =
                aiProxyService.ragChat(USER_ID, COMPANY_ID, new RagChatRequest("후속 질문입니다", SESSION_ID));

        assertThat(response.success()).isTrue();
        // 소유 검증은 RAG 유형까지 확인해야 한다(다른 유형 세션 재사용 차단).
        verify(chatSessionService).getOwnedSession(USER_ID, SESSION_ID, ChatSessionType.RAG);
        mockServer.verify();
    }

    @Test
    void ragChat_타인세션id_403_CHAT_SESSION_FORBIDDEN_AI서버호출없음() {
        // 핵심 보안 요건: 타인 소유 세션 식별자를 실어 보내면 FastAPI 호출·과금 전에 403으로 중단된다.
        doThrow(new BusinessException(ErrorCode.CHAT_SESSION_FORBIDDEN))
                .when(chatSessionService).getOwnedSession(USER_ID, OTHER_USER_SESSION_ID, ChatSessionType.RAG);

        assertThatThrownBy(() ->
                aiProxyService.ragChat(USER_ID, COMPANY_ID, new RagChatRequest("남의 세션 훔쳐보기", OTHER_USER_SESSION_ID)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHAT_SESSION_FORBIDDEN));
        mockServer.verify(); // 기대치 없음 = FastAPI 호출이 전혀 없어야 통과
    }

    @Test
    void ragChat_RAG아닌세션유형_403_CHAT_SESSION_FORBIDDEN_AI서버호출없음() {
        doThrow(new BusinessException(ErrorCode.CHAT_SESSION_FORBIDDEN))
                .when(chatSessionService).getOwnedSession(USER_ID, SESSION_ID, ChatSessionType.RAG);

        assertThatThrownBy(() ->
                aiProxyService.ragChat(USER_ID, COMPANY_ID, new RagChatRequest("상담 세션 재사용", SESSION_ID)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHAT_SESSION_FORBIDDEN));
        mockServer.verify();
    }

    // ---- 대화 맥락 유지: 이력 전달·저장(#1493/HAJA-657) ----

    private static ChatMessage message(ChatSenderType sender, String content) {
        ChatMessage message = ChatMessage.createText(SESSION_ID, sender, content);
        ReflectionTestUtils.setField(message, "id", (long) (Math.random() * 100000));
        return message;
    }

    @Test
    void ragChat_sessionId있음_이전이력있으면FastAPI요청바디에history포함() {
        // 리포지토리는 최신순(desc)으로 반환 — 호출부가 다시 asc로 뒤집는다.
        when(chatMessageRepository.findTop6BySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of(
                message(ChatSenderType.BOT, "1차 답변"),
                message(ChatSenderType.USER, "1차 질문")));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(content().json("""
                        {"question":"후속 질문입니다","history":[{"question":"1차 질문","answer":"1차 답변"}]}
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"후속 답변","sources":[]}}
                                """));

        ApiResponse<RagChatResponse> response =
                aiProxyService.ragChat(USER_ID, COMPANY_ID, new RagChatRequest("후속 질문입니다", SESSION_ID));

        assertThat(response.success()).isTrue();
        mockServer.verify();
    }

    @Test
    void ragChat_이력이3턴초과면최근3턴만FastAPI로전달() {
        // 리포지토리(findTop6BySessionIdOrderByCreatedAtDesc)가 이미 최근 6건(3턴)만 최신순으로 반환한다
        // (PR #1510 P2 픽스 — 세션 전체가 아니라 필요한 만큼만 DB에서 가져온다). 호출부는 그걸 다시
        // 시간순으로 뒤집어 프롬프트 순서를 맞춘다. "질문1/답변1"은 이미 DB 조회 단계에서 잘려나가
        // 여기 없다는 것 자체가 최근 3턴 제한이 지켜짐을 보여준다.
        when(chatMessageRepository.findTop6BySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of(
                message(ChatSenderType.BOT, "답변4"), message(ChatSenderType.USER, "질문4"),
                message(ChatSenderType.BOT, "답변3"), message(ChatSenderType.USER, "질문3"),
                message(ChatSenderType.BOT, "답변2"), message(ChatSenderType.USER, "질문2")));

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(content().json("""
                        {"history":[
                            {"question":"질문2","answer":"답변2"},
                            {"question":"질문3","answer":"답변3"},
                            {"question":"질문4","answer":"답변4"}
                        ]}
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"답변5","sources":[]}}
                                """));

        aiProxyService.ragChat(USER_ID, COMPANY_ID, new RagChatRequest("질문5", SESSION_ID));

        mockServer.verify();
    }

    @Test
    void ragChat_sessionId없음_이력전달없고저장도안함() {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(content().json("""
                        {"question":"균열 보수 기준은 무엇인가요?","history":[]}
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"답변","sources":[{
                                    "doc_id":"42","title":"t","collection":"regulations",
                                    "locator":"제1조","snippet":"s","chunk_ref":"42_0"}]}}
                                """));

        aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST);

        verifyNoInteractions(chatMessageRepository);
        verifyNoInteractions(ragConversationPersistenceService);
        mockServer.verify();
    }

    @Test
    void ragChat_sessionId있음_응답성공시대화저장서비스에위임() {
        when(chatMessageRepository.findTop6BySessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of());

        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"손상 정도에 따라 다릅니다.","sources":[
                                    {"doc_id":"42","title":"t","collection":"regulations",
                                     "locator":"제12조","snippet":"s","chunk_ref":"42_3"}
                                ]}}
                                """));

        aiProxyService.ragChat(USER_ID, COMPANY_ID, new RagChatRequest("균열 보수 기준은?", SESSION_ID));

        // 저장 자체(DB 쓰기)는 RagConversationPersistenceService 책임 — ragChat()은 위임만 검증한다
        // (PR #1510 P1 픽스: ragChat() 트랜잭션 범위에서 저장 로직을 분리).
        ArgumentCaptor<RagChatResponse> dataCaptor = ArgumentCaptor.forClass(RagChatResponse.class);
        verify(ragConversationPersistenceService)
                .saveConversation(eq(SESSION_ID), eq("균열 보수 기준은?"), dataCaptor.capture());
        assertThat(dataCaptor.getValue().answer()).isEqualTo("손상 정도에 따라 다릅니다.");
        assertThat(dataCaptor.getValue().sources()).hasSize(1);
        assertThat(dataCaptor.getValue().sources().get(0).chunkRef()).isEqualTo("42_3");
        mockServer.verify();
    }

    @Test
    void ragChat_sessionId있음_세션소유검증실패시대화저장호출안함() {
        doThrow(new BusinessException(ErrorCode.CHAT_SESSION_FORBIDDEN))
                .when(chatSessionService).getOwnedSession(USER_ID, OTHER_USER_SESSION_ID, ChatSessionType.RAG);

        assertThatThrownBy(() -> aiProxyService.ragChat(
                USER_ID, COMPANY_ID, new RagChatRequest("남의 세션", OTHER_USER_SESSION_ID)))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(chatMessageRepository);
        verifyNoInteractions(ragConversationPersistenceService);
    }

    // ── 시맨틱 캐시 회사 스코프 (#1584) ──────────────────────────────────────

    @Test
    void ragChat_principal의companyId를company_id로FastAPI에전달() {
        // AI 서버는 이 값으로 시맨틱 캐시 조회·저장 스코프를 회사 단위로 제한한다 — 필드명이
        // snake_case(company_id)가 아니면 Pydantic이 모르는 필드로 무시해 전역 조회가 유지된다.
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"question":"균열 보수 기준은 무엇인가요?","company_id":7}
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"답변","sources":[{
                                    "doc_id":"42","title":"t","collection":"regulations",
                                    "locator":"제1조","snippet":"s","chunk_ref":"42_0"}]}}
                                """));

        aiProxyService.ragChat(USER_ID, COMPANY_ID, REQUEST);

        mockServer.verify();
    }

    @Test
    void ragChat_개인회원_companyId없으면company_id를null로전달() {
        // 회사 미소속 개인회원(companyId=null)도 RAG 챗봇을 쓴다. AI 서버는 null을 받으면 시맨틱
        // 캐시를 조회·저장 모두 건너뛴다(fail-closed) — 여기서는 프록시가 값을 지어내지 않고
        // null 을 그대로 전달하는지만 검증한다.
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andExpect(content().json("""
                        {"question":"균열 보수 기준은 무엇인가요?","company_id":null}
                        """))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"답변","sources":[{
                                    "doc_id":"42","title":"t","collection":"regulations",
                                    "locator":"제1조","snippet":"s","chunk_ref":"42_0"}]}}
                                """));

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(USER_ID, null, REQUEST);

        assertThat(response.success()).isTrue();
        mockServer.verify();
    }
}
