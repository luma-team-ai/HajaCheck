package com.hajacheck.counsel.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatEntityTest {

    @Test
    void create_세션과시나리오메시지관계를구성() {
        ChatSession session = ChatSession.start(10L, ChatSessionType.SCENARIO_BOT);
        BotScenario root = BotScenario.create(null, "시설", "시설 점검", "항목을 선택하세요", false, 0);
        BotScenario child = BotScenario.create(root, "시설", "균열", "균열 정보를 안내합니다", false, 1);

        ChatMessage message = ChatMessage.create(session, ChatSenderType.USER, "균열", child);

        assertThat(message.getSession()).isSameAs(session);
        assertThat(message.getScenario()).isSameAs(child);
        assertThat(child.getParent()).isSameAs(root);
        assertThat(message.getSender()).isEqualTo(ChatSenderType.USER);
    }

    @Test
    void end_세션종료시각을기록() {
        ChatSession session = ChatSession.start(10L, ChatSessionType.RAG);

        session.end();

        assertThat(session.getEndedAt()).isNotNull();
    }
}
