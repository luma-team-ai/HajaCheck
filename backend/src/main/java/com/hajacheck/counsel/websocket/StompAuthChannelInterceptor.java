package com.hajacheck.counsel.websocket;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * WebSocket 인증 2단계 — 클라이언트 인바운드 채널 인터셉터.
 *
 * <ul>
 *   <li><b>CONNECT</b>: 핸드셰이크 이후 로그아웃/만료됐을 수 있으므로 세션을 <b>재조회</b>해 재검증한다
 *       (핸드셰이크 시점 속성만 신뢰하지 않음). 통과 시 {@link StompUserPrincipal} 을 STOMP 세션 주체로 설정.</li>
 *   <li><b>SUBSCRIBE</b>: {@code /topic/counsel/{ticketId}} 구독 시 요청자가 그 티켓의 당사자
 *       (사용자 본인 또는 담당 상담원)인지 검증한다 — 순차 PK 추측으로 남의 상담을 도청하는 것을 차단.</li>
 * </ul>
 *
 * 위반은 {@link MessageDeliveryException} 으로 거부 → 클라이언트는 ERROR 프레임을 받고 해당 프레임이 차단된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String TOPIC_PREFIX = "/topic/counsel/";

    private final CounselWsSessionAuthenticator sessionAuthenticator;
    private final CounselTicketRepository ticketRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    /** CONNECT 재검증 — 핸드셰이크가 심어둔 세션ID 로 세션을 재조회해 여전히 인증 상태인지 확인. */
    private void handleConnect(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            throw new MessageDeliveryException("상담 WebSocket 세션 속성 없음");
        }
        String springSessionId = (String) attributes.get(CounselHandshakeInterceptor.ATTR_SESSION_ID);
        Long handshakeUserId = (Long) attributes.get(CounselHandshakeInterceptor.ATTR_USER_ID);

        Long revalidatedUserId = sessionAuthenticator.resolveUserId(springSessionId);
        if (revalidatedUserId == null || !revalidatedUserId.equals(handshakeUserId)) {
            log.debug("상담 WebSocket CONNECT 거부 — 세션 재검증 실패");
            throw new MessageDeliveryException("상담 WebSocket 세션 재검증 실패");
        }
        accessor.setUser(new StompUserPrincipal(revalidatedUserId));
    }

    /** SUBSCRIBE 인가 — 티켓 토픽은 당사자만. 사용자 목적지(/user/**)는 Spring 이 세션별로 격리하므로 통과. */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            return;
        }
        Long ticketId = parseTicketId(destination);
        Long userId = principalUserId(accessor);
        if (ticketId == null || userId == null || !isParticipant(ticketId, userId)) {
            log.debug("상담 WebSocket SUBSCRIBE 거부 — 비당사자 구독 시도: destination={}", destination);
            throw new MessageDeliveryException("상담방 구독 권한 없음");
        }
    }

    private boolean isParticipant(Long ticketId, Long userId) {
        CounselTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            return false;
        }
        return Objects.equals(ticket.getUserId(), userId)
                || Objects.equals(ticket.getCounselorId(), userId);
    }

    private Long principalUserId(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompUserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    /** {@code /topic/counsel/{ticketId}} 에서 ticketId 추출. 형식 위반 시 null. */
    private Long parseTicketId(String destination) {
        String remainder = destination.substring(TOPIC_PREFIX.length());
        int slash = remainder.indexOf('/');
        String idPart = slash >= 0 ? remainder.substring(0, slash) : remainder;
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
