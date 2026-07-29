package com.hajacheck.counsel.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * StompAuthChannelInterceptor 단위테스트 — WebSocket 2단계 인증의 CONNECT 재검증 + SUBSCRIBE 당사자 검증
 * (남의 상담방 도청 차단, #20/HAJA-33 Critical).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StompAuthChannelInterceptorTest {

    private static final Long TICKET_ID = 50L;
    private static final Long USER_ID = 1L;
    private static final Long COUNSELOR_ID = 9L;
    private static final String SESSION_ID = "spring-session-id";

    @Mock
    private CounselWsSessionAuthenticator sessionAuthenticator;
    @Mock
    private CounselTicketRepository ticketRepository;
    @Mock
    private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(sessionAuthenticator, ticketRepository);
    }

    // ── CONNECT 재검증 ──

    @Test
    void CONNECT_세션재검증통과_주체설정() {
        when(sessionAuthenticator.resolveUserId(SESSION_ID)).thenReturn(USER_ID);
        when(sessionAuthenticator.resolveRole(SESSION_ID)).thenReturn(Role.USER);
        Message<byte[]> message = connectMessage(SESSION_ID, USER_ID);

        Message<?> result = interceptor.preSend(message, channel);

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(accessor.getUser()).isInstanceOf(StompUserPrincipal.class);
        assertThat(((StompUserPrincipal) accessor.getUser()).getUserId()).isEqualTo(USER_ID);
        assertThat(((StompUserPrincipal) accessor.getUser()).getRole()).isEqualTo(Role.USER);
    }

    @Test
    void CONNECT_세션만료_거부() {
        // 핸드셰이크 이후 로그아웃/만료 → 재조회 시 null.
        when(sessionAuthenticator.resolveUserId(SESSION_ID)).thenReturn(null);
        Message<byte[]> message = connectMessage(SESSION_ID, USER_ID);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void CONNECT_핸드셰이크userId와불일치_거부() {
        when(sessionAuthenticator.resolveUserId(SESSION_ID)).thenReturn(999L);
        Message<byte[]> message = connectMessage(SESSION_ID, USER_ID);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // ── SUBSCRIBE 당사자 검증 ──

    @Test
    void SUBSCRIBE_본인티켓_통과() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket()));
        Message<byte[]> message = subscribeMessage("/topic/counsel/" + TICKET_ID, USER_ID, Role.USER);

        interceptor.preSend(message, channel); // no throw
    }

    @Test
    void SUBSCRIBE_담당상담원_통과() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket()));
        Message<byte[]> message = subscribeMessage("/topic/counsel/" + TICKET_ID, COUNSELOR_ID, Role.COUNSELOR);

        interceptor.preSend(message, channel); // no throw
    }

    @Test
    void SUBSCRIBE_타인티켓_거부_도청차단() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket()));
        Message<byte[]> message = subscribeMessage("/topic/counsel/" + TICKET_ID, 999L, Role.USER);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void SUBSCRIBE_존재하지않는티켓_거부() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());
        Message<byte[]> message = subscribeMessage("/topic/counsel/" + TICKET_ID, USER_ID, Role.USER);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void SUBSCRIBE_사용자큐목적지_통과() {
        // /user/** 는 Spring 이 세션별로 격리하므로 티켓 검증 없이 통과.
        Message<byte[]> message = subscribeMessage("/user/queue/counsel/assigned", USER_ID, Role.USER);

        interceptor.preSend(message, channel); // no throw
    }

    // ── SUBSCRIBE 대기열 토픽(#1001 후속) ──

    @Test
    void SUBSCRIBE_대기열토픽_COUNSELOR_통과() {
        Message<byte[]> message = subscribeMessage("/topic/counsel-queue", COUNSELOR_ID, Role.COUNSELOR);

        interceptor.preSend(message, channel); // no throw
    }

    @Test
    void SUBSCRIBE_대기열토픽_PLATFORM_ADMIN_통과() {
        Message<byte[]> message = subscribeMessage("/topic/counsel-queue", 5L, Role.PLATFORM_ADMIN);

        interceptor.preSend(message, channel); // no throw
    }

    @Test
    void SUBSCRIBE_대기열토픽_일반유저_거부() {
        Message<byte[]> message = subscribeMessage("/topic/counsel-queue", USER_ID, Role.USER);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // ── message builders (mutable accessor) ──

    private Message<byte[]> connectMessage(String sessionId, Long handshakeUserId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CounselHandshakeInterceptor.ATTR_SESSION_ID, sessionId);
        attributes.put(CounselHandshakeInterceptor.ATTR_USER_ID, handshakeUserId);
        accessor.setSessionAttributes(attributes);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, Long principalUserId, Role role) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new StompUserPrincipal(principalUserId, role));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private CounselTicket ticket() {
        CounselTicket ticket = CounselTicket.request(USER_ID, CounselType.ANALYSIS_RESULT, 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의");
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        ReflectionTestUtils.setField(ticket, "status", CounselTicketStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(ticket, "counselorId", COUNSELOR_ID);
        return ticket;
    }
}
