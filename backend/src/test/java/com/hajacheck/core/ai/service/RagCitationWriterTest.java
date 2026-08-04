package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 출처 저장 writer 단위테스트 — DB 제약(unique·NOT NULL·varchar(100))을 건드리기 전에 걸러내는
 * 방어 로직(#1593)을 고정한다.
 */
class RagCitationWriterTest {

    private static final Long BOT_MESSAGE_ID = 200L;

    private ChatMessageCitationRepository chatMessageCitationRepository;
    private RagCitationWriter writer;

    @BeforeEach
    void setUp() {
        chatMessageCitationRepository = mock(ChatMessageCitationRepository.class);
        writer = new RagCitationWriter(chatMessageCitationRepository);
    }

    private static RagChatResponse.SourceCitation source(
            String docId, String locator, String snippet, String chunkRef) {
        return new RagChatResponse.SourceCitation(docId, "제목", "regulations", locator, snippet, chunkRef);
    }

    @Test
    void saveCitations_정상sources_전건저장() {
        writer.saveCitations(BOT_MESSAGE_ID, List.of(
                source("12", "3페이지", "0.3mm 이상 보수", "chunk-abc-123"),
                source("34", "5페이지", "허용 폭 기준", "chunk-def-456")));

        ArgumentCaptor<ChatMessageCitation> captor = ArgumentCaptor.forClass(ChatMessageCitation.class);
        verify(chatMessageCitationRepository, times(2)).save(captor.capture());
        List<ChatMessageCitation> saved = captor.getAllValues();
        assertThat(saved.get(0).getMessageId()).isEqualTo(BOT_MESSAGE_ID);
        assertThat(saved.get(0).getDocumentId()).isEqualTo(12L);
        assertThat(saved.get(0).getChunkRef()).isEqualTo("chunk-abc-123");
        assertThat(saved.get(0).getLocator()).isEqualTo("3페이지");
        assertThat(saved.get(0).getSnippet()).isEqualTo("0.3mm 이상 보수");
        assertThat(saved.get(1).getDocumentId()).isEqualTo(34L);
    }

    @Test
    void saveCitations_숫자파싱불가능한docId는건너뛴다() {
        writer.saveCitations(BOT_MESSAGE_ID, List.of(
                source("invalid-doc-id", "3페이지", "발췌", "chunk-1"),
                source("34", "5페이지", "발췌", "chunk-2")));

        ArgumentCaptor<ChatMessageCitation> captor = ArgumentCaptor.forClass(ChatMessageCitation.class);
        verify(chatMessageCitationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDocumentId()).isEqualTo(34L);
    }

    @Test
    @DisplayName("동일 (documentId, chunkRef) 중복 인용은 첫 건만 저장한다(unique 위반 선제 차단)")
    void saveCitations_중복인용은한건만저장() {
        writer.saveCitations(BOT_MESSAGE_ID, List.of(
                source("12", "3페이지", "발췌", "chunk-abc-123"),
                source("12", "3페이지(중복)", "다른 발췌", "chunk-abc-123"),
                source("34", "5페이지", "발췌", "chunk-def-456")));

        ArgumentCaptor<ChatMessageCitation> captor = ArgumentCaptor.forClass(ChatMessageCitation.class);
        verify(chatMessageCitationRepository, times(2)).save(captor.capture());
        List<ChatMessageCitation> saved = captor.getAllValues();
        // 먼저 등장한 인용의 locator 가 남는다.
        assertThat(saved.get(0).getLocator()).isEqualTo("3페이지");
        assertThat(saved.get(1).getDocumentId()).isEqualTo(34L);
    }

    @Test
    void saveCitations_documentId가같아도chunkRef가다르면모두저장() {
        // 중복 제거 키는 (documentId, chunkRef) 쌍이다 — 같은 문서의 다른 청크 인용은 정상 경로다.
        writer.saveCitations(BOT_MESSAGE_ID, List.of(
                source("12", "3페이지", "발췌1", "chunk-1"),
                source("12", "4페이지", "발췌2", "chunk-2")));

        verify(chatMessageCitationRepository, times(2)).save(any(ChatMessageCitation.class));
    }

    @Test
    @DisplayName("NOT NULL 컬럼(chunkRef·locator·snippet)이 비면 저장을 건너뛴다")
    void saveCitations_필수필드결측은건너뛴다() {
        writer.saveCitations(BOT_MESSAGE_ID, List.of(
                source("12", "3페이지", "발췌", null),
                source("13", "3페이지", "발췌", "   "),
                source("14", null, "발췌", "chunk-3"),
                source("15", "3페이지", null, "chunk-4"),
                source("16", "3페이지", "발췌", "chunk-5")));

        ArgumentCaptor<ChatMessageCitation> captor = ArgumentCaptor.forClass(ChatMessageCitation.class);
        verify(chatMessageCitationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDocumentId()).isEqualTo(16L);
    }

    @Test
    @DisplayName("chunkRef 가 varchar(100) 을 넘으면 저장을 건너뛴다(22001 방지)")
    void saveCitations_chunkRef길이초과는건너뛴다() {
        String tooLong = "c".repeat(101);
        writer.saveCitations(BOT_MESSAGE_ID, List.of(source("12", "3페이지", "발췌", tooLong)));

        verifyNoInteractions(chatMessageCitationRepository);
    }

    @Test
    void saveCitations_경계값100자는저장한다() {
        String exactly100 = "c".repeat(100);
        writer.saveCitations(BOT_MESSAGE_ID, List.of(source("12", "3페이지", "발췌", exactly100)));

        verify(chatMessageCitationRepository, times(1)).save(any(ChatMessageCitation.class));
    }
}
