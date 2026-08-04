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
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
