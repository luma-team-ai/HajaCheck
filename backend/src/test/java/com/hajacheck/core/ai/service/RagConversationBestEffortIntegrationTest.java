package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.service.ChatSessionService;
import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.RagChatRequest;
import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.ai.support.AiProxyRateLimiter;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * RAG 대화 저장이 best-effort 로 동작하는지 실제 트랜잭션 경계에서 검증하는 통합테스트(#1593).
 *
 * <p>순수 Mockito 단위테스트는 "예외를 잡았다"까지만 증명할 수 있고, {@code @Transactional} 프록시가
 * rollback-only 로 마킹된 트랜잭션을 커밋 시점에 어떻게 처리하는지는 재현하지 못한다. 이 테스트는 실제
 * PostgreSQL(Testcontainers)과 실제 {@link RagConversationPersistenceService} 빈(= 트랜잭션 프록시)을
 * 써서, 진짜 FK 제약 위반을 발생시킨 뒤에도 답변이 200으로 반환되는지를 고정한다.
 *
 * <p>{@link AiProxyService} 만 수동 조립한다 — FastAPI 호출은 {@link MockRestServiceServer} 로 스텁하고,
 * 저장 경로(트랜잭션이 걸린 부분)는 컨텍스트의 실제 빈을 그대로 주입한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class RagConversationBestEffortIntegrationTest extends PostgresTestSupport {

    private static final String AI_SERVER_URL = "http://ai-server-test/ai/rag-chat";
    /** rag_documents 에 존재하지 않는 식별자 — "Postgres 에서 삭제됐지만 Chroma 에 잔존하는 문서" 재현용. */
    private static final long DELETED_DOCUMENT_ID = 999_999_999L;

    @Autowired
    private RagConversationPersistenceService ragConversationPersistenceService;
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private ChatMessageCitationRepository chatMessageCitationRepository;
    @Autowired
    private RagDocumentRepository ragDocumentRepository;

    private AiProxyService aiProxyService;
    private MockRestServiceServer mockServer;

    private Long userId;
    private Long sessionId;
    private Long documentId;

    @BeforeEach
    void setUp() {
        long unique = System.nanoTime();
        User user = userRepository.save(User.builder()
                .email("rag-persist-" + unique + "@haja.com").name("RAG사용자").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build());
        userId = user.getId();
        sessionId = chatSessionRepository.save(ChatSession.start(userId, ChatSessionType.RAG)).getId();
        documentId = ragDocumentRepository.save(RagDocument.upload(
                "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
                null, "국토교통부", null, null, "rag-documents/stub.pdf")).getId();

        AiServerProperties properties = new AiServerProperties();
        properties.setBaseUrl("http://ai-server-test");
        properties.setInternalKey("test-internal-key");
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(60000);

        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        aiProxyService = new AiProxyService(
                builder.build(), properties, null, new AiProxyRateLimiter(new InMemoryRateLimiter()),
                builder.build(), chatSessionService, chatMessageRepository,
                ragConversationPersistenceService);
    }

    // 정적 Testcontainers 인스턴스는 모든 테스트 클래스가 공유한다 — 남기면 다른 테스트의 count/content
    // assertion 을 깨뜨린다(PR #1006 회귀 선례). FK 의존 역순으로 삭제.
    @AfterEach
    void tearDown() {
        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Long> messageIds = messages.stream().map(ChatMessage::getId).toList();
        if (!messageIds.isEmpty()) {
            chatMessageCitationRepository.deleteAll(
                    chatMessageCitationRepository.findByMessageIdIn(messageIds));
        }
        chatMessageRepository.deleteAll(messages);
        chatSessionRepository.deleteById(sessionId);
        ragDocumentRepository.deleteById(documentId);
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("삭제된 문서를 인용해 citation FK 위반이 나도 답변은 200으로 반환된다(저장은 best-effort)")
    void ragChat_citationFK위반_답변은정상반환된다() {
        stubAiServerAnswer(DELETED_DOCUMENT_ID);

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(
                userId, null, new RagChatRequest("균열 보수 기준은?", sessionId));

        // 핵심: 이미 LLM 비용을 쓴 답변이 저장 실패 때문에 500으로 뒤집히지 않는다.
        assertThat(response.success()).isTrue();
        assertThat(response.data().answer()).isEqualTo("손상 정도에 따라 다릅니다.");
        mockServer.verify();

        // 문서화된 트레이드오프: 저장 트랜잭션은 통째로 롤백되므로 이 턴은 이력에 남지 않는다.
        // (질문·답변까지 사라지지만, 답변 자체를 못 받는 것보다는 낫다는 판단 — AiProxyService 주석 참고.)
        assertThat(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("정상 경로에서는 질문·답변·citation 이 기존과 동일하게 저장된다(회귀 방지)")
    void ragChat_정상경로_대화와citation이저장된다() {
        stubAiServerAnswer(documentId);

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(
                userId, null, new RagChatRequest("균열 보수 기준은?", sessionId));

        assertThat(response.success()).isTrue();
        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getSender()).isEqualTo(ChatSenderType.USER);
        assertThat(messages.get(0).getContent()).isEqualTo("균열 보수 기준은?");
        assertThat(messages.get(1).getSender()).isEqualTo(ChatSenderType.BOT);

        List<ChatMessageCitation> citations = chatMessageCitationRepository.findByMessageIdIn(
                List.of(messages.get(1).getId()));
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).getDocumentId()).isEqualTo(documentId);
        assertThat(citations.get(0).getChunkRef()).isEqualTo("42_3");
    }

    @Test
    @DisplayName("동일 청크가 중복 인용돼도 unique 위반 없이 한 건만 저장된다")
    void saveConversation_중복인용_unique위반없이한건만저장된다() {
        RagChatResponse data = new RagChatResponse("답변", List.of(
                new RagChatResponse.SourceCitation(
                        String.valueOf(documentId), "t", "regulations", "제12조", "s", "42_3"),
                new RagChatResponse.SourceCitation(
                        String.valueOf(documentId), "t", "regulations", "제12조", "다른 발췌", "42_3")));

        ragConversationPersistenceService.saveConversation(sessionId, "중복 인용 질의", data);

        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        assertThat(messages).hasSize(2);
        assertThat(chatMessageCitationRepository.findByMessageIdIn(List.of(messages.get(1).getId())))
                .hasSize(1);
    }

    @Test
    @DisplayName("트랜잭션 경계 고정: saveConversation 은 예외를 삼키지 않고 프록시 밖으로 그대로 던진다")
    void saveConversation_저장실패시_원본예외가프록시경계밖으로전파된다() {
        // 이 테스트가 지키는 것은 "예외가 난다"가 아니라 "어느 예외가 나느냐"다.
        // saveConversation 내부에 try/catch 를 넣으면 트랜잭션은 이미 rollback-only 로 마킹된 뒤라
        // 커밋 시점에 UnexpectedRollbackException 이 새로 터진다 — 즉 서비스 내부에서 잡는 방식은
        // 저장 실패를 흡수하지 못한다. 원본 DataIntegrityViolationException 이 그대로 올라와야만
        // 호출부(AiProxyService.ragChat)의 try/catch 가 저장 트랜잭션만 롤백시키고 답변을 살릴 수 있다.
        RagChatResponse data = new RagChatResponse("답변", List.of(
                new RagChatResponse.SourceCitation(
                        String.valueOf(DELETED_DOCUMENT_ID), "t", "regulations", "제12조", "s", "42_3")));

        assertThatThrownBy(() ->
                ragConversationPersistenceService.saveConversation(sessionId, "삭제된 문서 인용", data))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void stubAiServerAnswer(long docId) {
        mockServer.expect(requestTo(AI_SERVER_URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"data":{"answer":"손상 정도에 따라 다릅니다.","sources":[
                                    {"doc_id":"%d","title":"t","collection":"regulations",
                                     "locator":"제12조","snippet":"s","chunk_ref":"42_3"}
                                ]}}
                                """.formatted(docId)));
    }
}
