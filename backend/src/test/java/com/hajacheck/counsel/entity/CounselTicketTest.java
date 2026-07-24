package com.hajacheck.counsel.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CounselTicketTest {

    @Test
    void assign_상담사와상담세션을배정() {
        CounselTicket ticket = CounselTicket.request(10L, CounselType.USAGE, 3);

        ticket.assign(20L, counselSession(5L, 10L));

        assertThat(ticket.getCounselorId()).isEqualTo(20L);
        assertThat(ticket.getSessionId()).isEqualTo(5L);
        assertThat(ticket.getStatus()).isEqualTo(CounselTicketStatus.IN_PROGRESS);
        assertThat(ticket.getQueuePosition()).isNull();
    }

    @Test
    void resolve_배정후상담종료상태와시각을기록() {
        CounselTicket ticket = CounselTicket.request(10L, CounselType.USAGE, 1);
        ticket.assign(20L, counselSession(5L, 10L));

        ticket.resolve();

        assertThat(ticket.getStatus()).isEqualTo(CounselTicketStatus.RESOLVED);
        assertThat(ticket.getEndedAt()).isNotNull();
    }

    @Test
    void resolve_대기중인티켓이면예외() {
        CounselTicket ticket = CounselTicket.request(10L, CounselType.USAGE, 1);

        assertThatThrownBy(ticket::resolve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assign_종료된티켓이면재배정과오프라인전환을거부() {
        CounselTicket ticket = CounselTicket.request(10L, CounselType.USAGE, 1);
        ticket.assign(20L, counselSession(5L, 10L));
        ticket.resolve();

        assertThatThrownBy(() -> ticket.assign(30L, counselSession(6L, 10L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(ticket::leaveOffline)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assign_유효한전문상담세션과상담사만허용() {
        CounselTicket ticket = CounselTicket.request(10L, CounselType.USAGE, 1);
        ChatSession scenarioSession = counselSession(5L, 10L);
        when(scenarioSession.getSessionType()).thenReturn(ChatSessionType.SCENARIO_BOT);
        ChatSession endedSession = counselSession(6L, 10L);
        when(endedSession.getEndedAt()).thenReturn(Instant.now());

        assertThatThrownBy(() -> ticket.assign(null, counselSession(4L, 10L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ticket.assign(20L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ticket.assign(20L, counselSession(null, 10L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ticket.assign(20L, scenarioSession))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ticket.assign(20L, counselSession(7L, 99L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ticket.assign(20L, endedSession))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ticket.getStatus()).isEqualTo(CounselTicketStatus.WAITING);
        assertThat(ticket.getCounselorId()).isNull();
        assertThat(ticket.getSessionId()).isNull();
    }

    @Test
    void request_상담유형이없으면예외() {
        assertThatThrownBy(() -> CounselTicket.request(10L, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChatSession counselSession(Long id, Long userId) {
        ChatSession session = mock(ChatSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getUserId()).thenReturn(userId);
        when(session.getSessionType()).thenReturn(ChatSessionType.COUNSEL);
        return session;
    }
}
