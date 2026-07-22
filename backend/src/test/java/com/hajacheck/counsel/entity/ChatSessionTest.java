package com.hajacheck.counsel.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatSessionTest {

    @Test
    void end_반복호출해도최초종료시각을유지() {
        ChatSession session = ChatSession.start(10L, ChatSessionType.COUNSEL);

        session.end();
        Instant firstEndedAt = session.getEndedAt();
        session.end();

        assertThat(session.getEndedAt()).isEqualTo(firstEndedAt);
    }
}
