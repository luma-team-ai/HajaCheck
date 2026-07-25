package com.hajacheck.counsel.service;

import com.hajacheck.counsel.dto.ChatMessageResponse;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담방 실시간 메시지 처리(FR-7, #20/HAJA-33). STOMP {@code SEND /app/counsel/{ticketId}/send} 진입점의
 * 도메인 로직 — 티켓이 IN_PROGRESS 가 아니거나 발신자가 참여자(사용자/담당 상담원)가 아니면 조용히 드롭한다.
 *
 * <p>발신자 자격은 이미 STOMP 인터셉터가 SUBSCRIBE 시점에 검증하지만, SEND 는 구독 없이도 보낼 수 있으므로
 * 서비스 계층에서 다시 참여자 여부를 확인한다(2중 방어 — 순차 PK 추측으로 남의 상담방에 주입 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounselChatService {

    private static final String TOPIC_PREFIX = "/topic/counsel/";

    private final CounselTicketRepository ticketRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 상담 메시지 저장 + 참여자에게 브로드캐스트. 드롭 조건(비진행 티켓·비참여자)은 예외 대신 로그 후 무시한다 —
     * WS 프레임 처리 중 예외는 클라이언트 세션을 끊을 수 있어, 방어적으로 조용히 무시하는 편이 안전하다.
     */
    @Transactional
    public void sendMessage(Long ticketId, Long senderUserId, String content) {
        CounselTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            log.debug("상담 메시지 드롭 — 티켓 없음: ticketId={}", ticketId);
            return;
        }
        if (ticket.getStatus() != CounselTicketStatus.IN_PROGRESS) {
            log.debug("상담 메시지 드롭 — 비진행 상태: ticketId={}, status={}", ticketId, ticket.getStatus());
            return;
        }
        ChatSenderType sender = resolveSender(ticket, senderUserId);
        if (sender == null) {
            log.debug("상담 메시지 드롭 — 비참여자 발신: ticketId={}", ticketId);
            return;
        }

        ChatMessage saved = chatMessageRepository.save(
                ChatMessage.create(ticket.getSessionId(), sender, content, null));
        messagingTemplate.convertAndSend(TOPIC_PREFIX + ticketId, ChatMessageResponse.from(saved));
    }

    /** 발신자가 티켓 사용자면 USER, 담당 상담원이면 COUNSELOR, 둘 다 아니면 null(비참여자). */
    private ChatSenderType resolveSender(CounselTicket ticket, Long senderUserId) {
        if (Objects.equals(ticket.getUserId(), senderUserId)) {
            return ChatSenderType.USER;
        }
        if (Objects.equals(ticket.getCounselorId(), senderUserId)) {
            return ChatSenderType.COUNSELOR;
        }
        return null;
    }
}
