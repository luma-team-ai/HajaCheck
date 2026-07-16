package com.hajacheck.counsel.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CounselTicketTest {

    @Test
    void assign_상담사와상담세션을배정() {
        CounselTicket ticket = CounselTicket.request(10L, 3);
        ChatSession session = ChatSession.start(10L, ChatSessionType.COUNSEL);

        ticket.assign(20L, session);

        assertThat(ticket.getCounselorId()).isEqualTo(20L);
        assertThat(ticket.getSession()).isSameAs(session);
        assertThat(ticket.getStatus()).isEqualTo(CounselTicketStatus.IN_PROGRESS);
        assertThat(ticket.getQueuePosition()).isNull();
    }

    @Test
    void resolve_상담종료상태와시각을기록() {
        CounselTicket ticket = CounselTicket.request(10L, 1);

        ticket.resolve();

        assertThat(ticket.getStatus()).isEqualTo(CounselTicketStatus.RESOLVED);
        assertThat(ticket.getEndedAt()).isNotNull();
    }
}
