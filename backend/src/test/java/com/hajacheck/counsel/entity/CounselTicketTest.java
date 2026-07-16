package com.hajacheck.counsel.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CounselTicketTest {

    @Test
    void assign_changesWaitingTicketToInProgress() {
        CounselTicket ticket = CounselTicket.request(10L, 3);

        ticket.assign(20L, 5L);

        assertThat(ticket.getCounselorId()).isEqualTo(20L);
        assertThat(ticket.getSessionId()).isEqualTo(5L);
        assertThat(ticket.getStatus()).isEqualTo(CounselTicketStatus.IN_PROGRESS);
        assertThat(ticket.getQueuePosition()).isNull();
    }

    @Test
    void resolve_recordsEndTimeAfterAssignment() {
        CounselTicket ticket = CounselTicket.request(10L, 1);
        ticket.assign(20L, 5L);

        ticket.resolve();

        assertThat(ticket.getStatus()).isEqualTo(CounselTicketStatus.RESOLVED);
        assertThat(ticket.getEndedAt()).isNotNull();
    }

    @Test
    void resolve_rejectsWaitingTicket() {
        CounselTicket ticket = CounselTicket.request(10L, 1);

        assertThatThrownBy(ticket::resolve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assign_rejectsResolvedTicket() {
        CounselTicket ticket = CounselTicket.request(10L, 1);
        ticket.assign(20L, 5L);
        ticket.resolve();

        assertThatThrownBy(() -> ticket.assign(30L, 6L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(ticket::leaveOffline)
                .isInstanceOf(IllegalStateException.class);
    }
}
