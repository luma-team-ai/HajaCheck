package com.hajacheck.counsel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.counsel.dto.ChatSessionCreateRequest;
import com.hajacheck.counsel.dto.ChatSessionMessageResponse;
import com.hajacheck.counsel.dto.ChatSessionResponse;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** ChatSessionService 단위테스트(#1467/HAJA-647) — 소유자 검증과 이력 조회 규칙. */
class ChatSessionServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long SESSION_ID = 100L;

    private ChatSessionRepository chatSessionRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatMessageCitationRepository chatMessageCitationRepository;
    private ChatSessionService chatSessionService;

    @BeforeEach
    void setUp() {
        chatSessionRepository = mock(ChatSessionRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        chatMessageCitationRepository = mock(ChatMessageCitationRepository.class);
        chatSessionService = new ChatSessionService(
                chatSessionRepository, chatMessageRepository, chatMessageCitationRepository);
    }

    @Test
    void createSession_로그인사용자소유로생성() {
        ChatSession saved = ChatSession.start(OWNER_ID, ChatSessionType.RAG);
        when(chatSessionRepository.save(any(ChatSession.class))).thenReturn(saved);

        ChatSessionResponse response = chatSessionService.createSession(
                OWNER_ID, new ChatSessionCreateRequest(ChatSessionType.RAG));

        assertThat(response.sessionType()).isEqualTo(ChatSessionType.RAG);
        assertThat(response.startedAt()).isNotNull();
    }

    @Test
    void findMessages_소유자본인_이력반환_인용은비어있어도조회성공() {
        ChatSession session = ChatSession.start(OWNER_ID, ChatSessionType.RAG);
        when(chatSessionRepository.findByIdAndUserId(SESSION_ID, OWNER_ID))
                .thenReturn(Optional.of(session));
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()))
                .thenReturn(List.of(
                        ChatMessage.createText(SESSION_ID, ChatSenderType.USER, "질문"),
                        ChatMessage.createText(SESSION_ID, ChatSenderType.BOT, "답변")));
        when(chatMessageCitationRepository.findByMessageIdIn(any())).thenReturn(List.of());

        List<ChatSessionMessageResponse> messages =
                chatSessionService.findMessages(OWNER_ID, SESSION_ID);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).sender()).isEqualTo(ChatSenderType.USER);
        assertThat(messages.get(0).citations()).isEmpty();
        assertThat(messages.get(1).sender()).isEqualTo(ChatSenderType.BOT);
    }

    @Test
    void findMessages_인용문서의title과collection을소문자로매핑한다() {
        ChatSession session = ChatSession.start(OWNER_ID, ChatSessionType.RAG);
        ChatMessage botMessage = ChatMessage.createText(SESSION_ID, ChatSenderType.BOT, "답변");
        Long botMessageId = 999L;
        // id는 @GeneratedValue(IDENTITY)라 영속화 전엔 null — citation과 짝지을 messageId가 필요해
        // reflection으로 주입한다(다른 필드는 create() 생성자 인자로 이미 세팅됨).
        ReflectionTestUtils.setField(botMessage, "id", botMessageId);
        RagDocument document = RagDocument.upload(
                "시설물의 안전 및 유지관리에 관한 특별법", RagDocumentSourceType.LAW,
                RagTargetCollection.REGULATIONS, null, "국토교통부", null, null, "rag-documents/stub.pdf");
        ChatMessageCitation citation = ChatMessageCitation.create(
                botMessageId, 12L, "12_3", "제11조 ①", "시설물 안전 발췌");
        // document 는 insertable/updatable=false 조회 전용 연관관계라 create() 로 직접 못 넣는다
        // (실제로는 JPA join 으로 채워진다) — 단위테스트는 매핑 로직만 검증하므로 reflection 으로 주입한다.
        ReflectionTestUtils.setField(citation, "document", document);

        when(chatSessionRepository.findByIdAndUserId(SESSION_ID, OWNER_ID))
                .thenReturn(Optional.of(session));
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()))
                .thenReturn(List.of(botMessage));
        when(chatMessageCitationRepository.findByMessageIdIn(any())).thenReturn(List.of(citation));

        List<ChatSessionMessageResponse> messages =
                chatSessionService.findMessages(OWNER_ID, SESSION_ID);

        assertThat(messages).hasSize(1);
        ChatSessionMessageResponse.Citation mapped = messages.get(0).citations().get(0);
        assertThat(mapped.documentId()).isEqualTo(12L);
        assertThat(mapped.title()).isEqualTo("시설물의 안전 및 유지관리에 관한 특별법");
        assertThat(mapped.collection()).isEqualTo("regulations");
        assertThat(mapped.chunkRef()).isEqualTo("12_3");
        assertThat(mapped.locator()).isEqualTo("제11조 ①");
        assertThat(mapped.snippet()).isEqualTo("시설물 안전 발췌");
    }

    @Test
    void findMessages_메시지없음_인용조회하지않고빈목록() {
        when(chatSessionRepository.findByIdAndUserId(SESSION_ID, OWNER_ID))
                .thenReturn(Optional.of(ChatSession.start(OWNER_ID, ChatSessionType.RAG)));
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        assertThat(chatSessionService.findMessages(OWNER_ID, SESSION_ID)).isEmpty();
        verifyNoInteractions(chatMessageCitationRepository);
    }

    @Test
    void findMessages_타인세션_403_CHAT_SESSION_FORBIDDEN() {
        // 소유자 조건을 쿼리에 넣으므로 타인 요청은 빈 Optional → 403.
        when(chatSessionRepository.findByIdAndUserId(SESSION_ID, OTHER_USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatSessionService.findMessages(OTHER_USER_ID, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHAT_SESSION_FORBIDDEN));
        // 인가 실패 시 메시지를 읽지 않는다(내용 유출 방지).
        verifyNoInteractions(chatMessageRepository);
    }

    @Test
    void getOwnedSession_세션유형불일치_403_CHAT_SESSION_FORBIDDEN() {
        when(chatSessionRepository.findByIdAndUserId(SESSION_ID, OWNER_ID))
                .thenReturn(Optional.of(ChatSession.start(OWNER_ID, ChatSessionType.COUNSEL)));

        assertThatThrownBy(() ->
                chatSessionService.getOwnedSession(OWNER_ID, SESSION_ID, ChatSessionType.RAG))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHAT_SESSION_FORBIDDEN));
    }

    @Test
    void getOwnedSession_소유자와유형일치_세션반환() {
        ChatSession session = ChatSession.start(OWNER_ID, ChatSessionType.RAG);
        when(chatSessionRepository.findByIdAndUserId(SESSION_ID, OWNER_ID))
                .thenReturn(Optional.of(session));

        assertThat(chatSessionService.getOwnedSession(OWNER_ID, SESSION_ID, ChatSessionType.RAG))
                .isSameAs(session);
    }
}
