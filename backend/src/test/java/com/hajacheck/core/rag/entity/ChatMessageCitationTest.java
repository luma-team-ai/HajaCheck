package com.hajacheck.core.rag.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatMessageCitationTest {

    @Test
    void create_PostgreSQL문서와Chroma청크참조를분리() {
        ChatMessageCitation citation = ChatMessageCitation.create(
                10L, 20L, "regulations:20:chunk-3", "제12조 ①", "시설물의 안전점검");

        assertThat(citation.getMessageId()).isEqualTo(10L);
        assertThat(citation.getDocumentId()).isEqualTo(20L);
        assertThat(citation.getChunkRef()).isEqualTo("regulations:20:chunk-3");
        assertThat(citation.getLocator()).isEqualTo("제12조 ①");
    }
}
