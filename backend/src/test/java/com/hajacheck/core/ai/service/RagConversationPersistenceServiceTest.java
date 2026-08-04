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

    /** BOT 메시지에 id가 채워진 상태로 저장되도록 스텁 — citation 저장에 messageId가 필요하다. */
    private void stubChatMessageSave() {
        ChatMessage userMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.USER, QUERY);
        ChatMessage botMessageMock = ChatMessage.createText(SESSION_ID, ChatSenderType.BOT, ANSWER);
        ReflectionTestUtils.setField(botMessageMock, "id", 200L);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage msg = invocation.getArgument(0);
            return msg.getSender() == ChatSenderType.BOT ? botMessageMock : userMessageMock;
        });
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
    void saveConversation_동일documentId와chunkRef가중복인용되면한건만저장() {
        // given: 리랭킹 결과에 같은 청크가 두 번 등장한 상황(#1593). 그대로 저장하면
        // unique(message_id, document_id, chunk_ref) 위반 → DataIntegrityViolationException 으로
        // 대화 전체가 롤백된다.
        List<RagChatResponse.SourceCitation> sources = List.of(
                new RagChatResponse.SourceCitation("12", "균열관리기준", "regulations", "3페이지", "0.3mm 이상 보수", "chunk-abc-123"),
                new RagChatResponse.SourceCitation("12", "균열관리기준", "regulations", "3페이지(중복)", "다른 발췌", "chunk-abc-123"),
                new RagChatResponse.SourceCitation("34", "하자판정기준", "regulations", "5페이지", "허용 폭 기준", "chunk-def-456")
        );
        RagChatResponse data = new RagChatResponse(ANSWER, sources);
        stubChatMessageSave();

        // when
        service.saveConversation(SESSION_ID, QUERY, data);

        // then: 중복된 (12, chunk-abc-123) 은 첫 건만 저장되고 두 번째는 생략 → 총 2건
        ArgumentCaptor<ChatMessageCitation> citationCaptor = ArgumentCaptor.forClass(ChatMessageCitation.class);
        verify(chatMessageCitationRepository, times(2)).save(citationCaptor.capture());

        List<ChatMessageCitation> saved = citationCaptor.getAllValues();
        assertThat(saved.get(0).getDocumentId()).isEqualTo(12L);
        assertThat(saved.get(0).getChunkRef()).isEqualTo("chunk-abc-123");
        // 먼저 등장한 인용의 locator/snippet 이 남는다.
        assertThat(saved.get(0).getLocator()).isEqualTo("3페이지");
        assertThat(saved.get(1).getDocumentId()).isEqualTo(34L);
        assertThat(saved.get(1).getChunkRef()).isEqualTo("chunk-def-456");
    }

    @Test
    void saveConversation_documentId가같아도chunkRef가다르면모두저장() {
        // 중복 제거 키는 (documentId, chunkRef) 쌍이다 — 같은 문서의 서로 다른 청크를 인용하는 것은
        // 정상 경로이므로 하나로 합쳐버리면 안 된다(과잉 제거 회귀 방지).
        List<RagChatResponse.SourceCitation> sources = List.of(
                new RagChatResponse.SourceCitation("12", "균열관리기준", "regulations", "3페이지", "발췌1", "chunk-1"),
                new RagChatResponse.SourceCitation("12", "균열관리기준", "regulations", "4페이지", "발췌2", "chunk-2")
        );
        RagChatResponse data = new RagChatResponse(ANSWER, sources);
        stubChatMessageSave();

        service.saveConversation(SESSION_ID, QUERY, data);

        verify(chatMessageCitationRepository, times(2)).save(any(ChatMessageCitation.class));
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
