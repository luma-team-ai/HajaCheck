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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * RAG 대화 저장이 best-effort 로 동작하는지 실제 트랜잭션 경계에서 검증하는 통합테스트(#1593).
 *
 * <p>순수 Mockito 단위테스트는 "예외를 잡았다"까지만 증명할 수 있고, {@code @Transactional} 프록시가
 * rollback-only 로 마킹된 트랜잭션을 커밋 시점에 어떻게 처리하는지는 재현하지 못한다. 이 테스트는 실제
 * PostgreSQL(Testcontainers)과 실제 저장 빈들({@link RagChatMessageWriter}·{@link RagCitationWriter} 의
 * 트랜잭션 프록시)을 써서, 진짜 FK 제약 위반을 발생시킨 뒤에도 ① 답변이 200으로 반환되고 ② 질문·답변
 * 이력이 남으며 ③ 바깥 트랜잭션이 있어도 그 커밋이 오염되지 않는지를 고정한다.
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
    private RagChatMessageWriter ragChatMessageWriter;
    @Autowired
    private RagCitationWriter ragCitationWriter;
    @Autowired
    private PlatformTransactionManager transactionManager;
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
    private TransactionTemplate transactionTemplate;

    private Long userId;
    private Long sessionId;
    private Long documentId;
    /** 바깥 트랜잭션 테스트에서 만든 세션 — 커밋 생존 확인 후 정리한다. */
    private Long outerWriteSessionId;

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
        // 3번째 인자 briefingStatsService=null — ragChat 경로에서는 참조되지 않는 협력자라
        // 컨텍스트 빈을 끌어오지 않고 null 로 둔다(다른 인자와 달리 의도적 미주입).
        aiProxyService = new AiProxyService(
                builder.build(), properties, null, new AiProxyRateLimiter(new InMemoryRateLimiter()),
                builder.build(), chatSessionService, chatMessageRepository,
                ragConversationPersistenceService);
        transactionTemplate = new TransactionTemplate(transactionManager);
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
        if (outerWriteSessionId != null) {
            chatSessionRepository.deleteById(outerWriteSessionId);
            outerWriteSessionId = null;
        }
        ragDocumentRepository.deleteById(documentId);
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("삭제된 문서를 인용해 citation FK 위반이 나도 답변은 200이고, 질문·답변 이력은 남는다")
    void ragChat_citationFK위반_답변은정상반환되고이력도보존된다() {
        stubAiServerAnswer(DELETED_DOCUMENT_ID);

        ApiResponse<RagChatResponse> response = aiProxyService.ragChat(
                userId, null, new RagChatRequest("균열 보수 기준은?", sessionId));

        // ① 이미 LLM 비용을 쓴 답변이 저장 실패 때문에 500으로 뒤집히지 않는다.
        assertThat(response.success()).isTrue();
        assertThat(response.data().answer()).isEqualTo("손상 정도에 따라 다릅니다.");
        mockServer.verify();

        // ② 메시지와 출처가 별도 물리 트랜잭션이므로, 출처만 실패하고 질문·답변은 커밋된 채 남는다.
        //    (한 트랜잭션이던 시절엔 출처 한 건 때문에 이 턴 전체가 사라졌다 — #1593 리뷰 P2.)
        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getContent()).isEqualTo("균열 보수 기준은?");
        assertThat(messages.get(1).getSender()).isEqualTo(ChatSenderType.BOT);

        // ③ 남은 트레이드오프는 출처 칩 유실뿐이다.
        assertThat(chatMessageCitationRepository.findByMessageIdIn(List.of(messages.get(1).getId())))
                .isEmpty();
    }

    @Test
    @DisplayName("바깥 @Transactional 안에서 호출해도 저장 실패가 호출자 커밋을 오염시키지 않는다")
    void ragChat_바깥트랜잭션안에서호출_저장실패해도바깥커밋이살아남는다() {
        // 오늘의 호출 경로엔 바깥 트랜잭션이 없지만, 훗날 @Transactional 서비스가 ragChat 을 호출하면
        // 저장이 바깥 트랜잭션에 참여(REQUIRED)해 제약 위반이 바깥을 rollback-only 로 오염시키고,
        // 우리 catch 가 예외를 삼킨 뒤 바깥 커밋에서 UnexpectedRollbackException 이 터진다
        // (= 이번 픽스가 막으려던 실패 모드의 부활 + 호출자의 다른 쓰기까지 유실).
        // 저장 writer 들의 REQUIRES_NEW 가 그 전제를 구조로 못박는지 검증한다.
        stubAiServerAnswer(DELETED_DOCUMENT_ID);

        ApiResponse<RagChatResponse> response = transactionTemplate.execute(status -> {
            // 호출자가 자기 트랜잭션에서 수행하는 별개의 쓰기 — 저장 실패에 휩쓸리면 안 된다.
            outerWriteSessionId = chatSessionRepository.save(
                    ChatSession.start(userId, ChatSessionType.RAG)).getId();
            return aiProxyService.ragChat(userId, null, new RagChatRequest("균열 보수 기준은?", sessionId));
        });

        assertThat(response).isNotNull();
        assertThat(response.success()).isTrue();
        // 바깥 커밋이 UnexpectedRollbackException 없이 끝났고, 호출자의 쓰기도 살아남았다.
        assertThat(chatSessionRepository.findById(outerWriteSessionId)).isPresent();
        // 대화 이력도 그대로 커밋돼 있다(출처만 유실).
        assertThat(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).hasSize(2);
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
    @DisplayName("트랜잭션 경계 고정: 출처 writer 는 예외를 삼키지 않고 프록시 밖으로 원본을 던진다")
    void saveCitations_저장실패시_원본예외가프록시경계밖으로전파된다() {
        // 이 테스트가 지키는 것은 "예외가 난다"가 아니라 "어느 예외가 나느냐"다.
        // writer 내부에 try/catch 를 넣으면 그 트랜잭션은 이미 rollback-only 로 마킹된 뒤라 커밋
        // 시점에 UnexpectedRollbackException 이 새로 터진다 — 즉 writer 안에서 잡는 방식으로는
        // 저장 실패를 흡수할 수 없다. 원본 DataIntegrityViolationException 이 그대로 올라와야만
        // 호출부(오케스트레이터)의 catch 가 출처 트랜잭션만 버리고 대화 이력을 지킬 수 있다.
        Long botMessageId = ragChatMessageWriter.saveMessages(sessionId, "질문", "답변");

        assertThatThrownBy(() -> ragCitationWriter.saveCitations(botMessageId, List.of(
                new RagChatResponse.SourceCitation(
                        String.valueOf(DELETED_DOCUMENT_ID), "t", "regulations", "제12조", "s", "42_3"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("출처 트랜잭션은 메시지 커밋 뒤에 실행돼야 한다 — message_id FK 가 커밋된 행만 본다")
    void saveCitations_메시지커밋후에실행되므로_messageIdFK가만족된다() {
        // 순서 제약의 근거를 고정한다: 출처 writer 가 REQUIRES_NEW 인 이상, 메시지 트랜잭션 "안에서"
        // 중첩 호출하면 아직 커밋되지 않은 봇 메시지를 볼 수 없어 정상 경로에서도 message_id FK 가
        // 깨진다. 그래서 오케스트레이터는 중첩이 아니라 순차로 부른다.
        Long botMessageId = ragChatMessageWriter.saveMessages(sessionId, "질문", "답변");

        ragCitationWriter.saveCitations(botMessageId, List.of(
                new RagChatResponse.SourceCitation(
                        String.valueOf(documentId), "t", "regulations", "제12조", "s", "42_3")));

        assertThat(chatMessageCitationRepository.findByMessageIdIn(List.of(botMessageId))).hasSize(1);
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
