package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class RagConversationPersistenceServiceTest {

    private ChatMessageRepository chatMessageRepository;
    private ChatMessageCitationRepository chatMessageCitationRepository;
    private RagConversationPersistenceService service;

    private static final Long SESSION_ID = 100L;
    private static final String QUERY = "균열 보수 기준은 무엇인가요?";
    private static final String ANSWER = "균열 폭이 0.3mm 이상인 경우 보수해야 합니다.";

    @BeforeEach
    void setUp() {
        chatMessageRepository = mock(ChatMessageRepository.class);
        chatMessageCitationRepository = mock(ChatMessageCitationRepository.class);
        service = new RagConversationPersistenceService(chatMessageRepository, chatMessageCitationRepository);
    }

    @Test
    void saveConversation_정상sources_ChatMessage2건과CitationN건저장() {
        // given
        List<RagChatResponse.SourceCitation> sources = List.of(
                new RagChatResponse.SourceCitation("12", "균열관리기준", "regulations", "3페이지", "0.3mm 이상 보수", "chunk-abc-123"),
                new RagChatResponse.SourceCitation("34", "하자판정기준", "regulations", "5페이지", "허용 폭 기준", "chunk-def-456")
        );
        RagChatResponse data = new RagChatResponse(ANSWER, sources);

        ChatMessage userMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.USER, QUERY);
        ChatMessage botMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.BOT, ANSWER);
        ReflectionTestUtils.setField(botMessageMock, "id", 200L);

        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage msg = invocation.getArgument(0);
            if (msg.getSender() == ChatSenderType.BOT) {
                return botMessageMock;
            }
            return userMessageMock;
        });

        // when
        service.saveConversation(SESSION_ID, QUERY, data);

        // then
        // 1. ChatMessage USER/BOT 2건 저장 검증
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(messageCaptor.capture());

        List<ChatMessage> savedMessages = messageCaptor.getAllValues();
        assertThat(savedMessages.get(0).getSender()).isEqualTo(ChatSenderType.USER);
        assertThat(savedMessages.get(0).getContent()).isEqualTo(QUERY);
        assertThat(savedMessages.get(1).getSender()).isEqualTo(ChatSenderType.BOT);
        assertThat(savedMessages.get(1).getContent()).isEqualTo(ANSWER);

        // 2. ChatMessageCitation 2건 저장 검증
        ArgumentCaptor<ChatMessageCitation> citationCaptor = ArgumentCaptor.forClass(ChatMessageCitation.class);
        verify(chatMessageCitationRepository, times(2)).save(citationCaptor.capture());

        List<ChatMessageCitation> savedCitations = citationCaptor.getAllValues();
        assertThat(savedCitations.get(0).getMessageId()).isEqualTo(200L);
        assertThat(savedCitations.get(0).getDocumentId()).isEqualTo(12L);
        assertThat(savedCitations.get(0).getChunkRef()).isEqualTo("chunk-abc-123");
        assertThat(savedCitations.get(0).getLocator()).isEqualTo("3페이지");
        assertThat(savedCitations.get(0).getSnippet()).isEqualTo("0.3mm 이상 보수");

        assertThat(savedCitations.get(1).getMessageId()).isEqualTo(200L);
        assertThat(savedCitations.get(1).getDocumentId()).isEqualTo(34L);
        assertThat(savedCitations.get(1).getChunkRef()).isEqualTo("chunk-def-456");
        assertThat(savedCitations.get(1).getLocator()).isEqualTo("5페이지");
        assertThat(savedCitations.get(1).getSnippet()).isEqualTo("허용 폭 기준");
    }

    @Test
    void saveConversation_숫자파싱불가능한docId가있으면citation저장건너뜀() {
        // given
        List<RagChatResponse.SourceCitation> sources = List.of(
                new RagChatResponse.SourceCitation("invalid-doc-id", "균열관리기준", "regulations", "3페이지", "0.3mm 이상 보수", "chunk-abc-123"),
                new RagChatResponse.SourceCitation("34", "하자판정기준", "regulations", "5페이지", "허용 폭 기준", "chunk-def-456")
        );
        RagChatResponse data = new RagChatResponse(ANSWER, sources);

        ChatMessage userMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.USER, QUERY);
        ChatMessage botMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.BOT, ANSWER);
        ReflectionTestUtils.setField(botMessageMock, "id", 200L);

        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage msg = invocation.getArgument(0);
            if (msg.getSender() == ChatSenderType.BOT) {
                return botMessageMock;
            }
            return userMessageMock;
        });

        // when
        service.saveConversation(SESSION_ID, QUERY, data);

        // then
        // 1. ChatMessage USER/BOT 2건은 저장됨
        verify(chatMessageRepository, times(2)).save(any(ChatMessage.class));

        // 2. 파싱 불가능한 "invalid-doc-id"는 건너뛰고 "34"만 1건 저장됨
        ArgumentCaptor<ChatMessageCitation> citationCaptor = ArgumentCaptor.forClass(ChatMessageCitation.class);
        verify(chatMessageCitationRepository, times(1)).save(citationCaptor.capture());

        ChatMessageCitation savedCitation = citationCaptor.getValue();
        assertThat(savedCitation.getMessageId()).isEqualTo(200L);
        assertThat(savedCitation.getDocumentId()).isEqualTo(34L);
        assertThat(savedCitation.getChunkRef()).isEqualTo("chunk-def-456");
        assertThat(savedCitation.getLocator()).isEqualTo("5페이지");
        assertThat(savedCitation.getSnippet()).isEqualTo("허용 폭 기준");
    }

    @Test
    void saveConversation_sources가null이면citation저장없이메시지만저장() {
        // given
        RagChatResponse data = new RagChatResponse(ANSWER, null);

        ChatMessage userMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.USER, QUERY);
        ChatMessage botMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.BOT, ANSWER);
        ReflectionTestUtils.setField(botMessageMock, "id", 200L);

        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage msg = invocation.getArgument(0);
            if (msg.getSender() == ChatSenderType.BOT) {
                return botMessageMock;
            }
            return userMessageMock;
        });

        // when
        service.saveConversation(SESSION_ID, QUERY, data);

        // then
        // ChatMessage 2건 저장
        verify(chatMessageRepository, times(2)).save(any(ChatMessage.class));
        // Citation은 저장 시도조차 하지 않음
        verifyNoInteractions(chatMessageCitationRepository);
    }
}
