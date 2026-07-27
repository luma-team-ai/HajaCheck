package com.hajacheck.counsel.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.counsel.dto.ChatMessageResponse;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.counsel.support.CounselAttachmentPolicy;
import com.hajacheck.counsel.support.CounselReplyNotificationPayload;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
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
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationService notificationService;

    /**
     * 상담 메시지 저장 + 참여자에게 브로드캐스트. 드롭 조건(비진행 티켓·비참여자)은 예외 대신 로그 후 무시한다 —
     * WS 프레임 처리 중 예외는 클라이언트 세션을 끊을 수 있어, 방어적으로 조용히 무시하는 편이 안전하다.
     */
    @Transactional
    public void sendMessage(Long ticketId, Long senderUserId, String content, String attachmentKey) {
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

        // 첨부 검증: 상담 카테고리 저장키만 허용(다른 도메인 저장키 참조 차단), MIME 은 확장자로 재도출(클라 불신).
        String attachmentMimeType = null;
        String validatedKey = null;
        if (StringUtils.hasText(attachmentKey)) {
            attachmentMimeType = CounselAttachmentPolicy.mimeTypeOf(attachmentKey);
            if (!CounselAttachmentPolicy.isCounselAttachmentKey(attachmentKey) || attachmentMimeType == null) {
                log.debug("상담 메시지 드롭 — 유효하지 않은 첨부 저장키: ticketId={}", ticketId);
                return;
            }
            validatedKey = attachmentKey;
        }

        boolean hasText = StringUtils.hasText(content);
        if (!hasText && validatedKey == null) {
            log.debug("상담 메시지 드롭 — 내용/첨부 모두 없음: ticketId={}", ticketId);
            return;
        }

        // content 는 DB NOT NULL 이라 이미지 전용 메시지는 빈 문자열로 저장한다.
        ChatMessage saved = chatMessageRepository.save(ChatMessage.create(
                ticket.getSessionId(), sender, hasText ? content : "", null, validatedKey, attachmentMimeType));
        String counselorName = sender == ChatSenderType.COUNSELOR
                ? userRepository.findById(ticket.getCounselorId()).map(User::getName).orElse(null)
                : null;
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + ticketId, ChatMessageResponse.from(saved, ticketId, counselorName));

        // 상담원 답변만 알림 대상(NOTI-01 나머지, #493) — 사용자 발신은 알림 불필요. 알림 발행 실패가 이미
        // 저장·브로드캐스트된 메시지 처리 자체를 막으면 안 되므로, 이 메서드의 기존 방어적 설계(위 클래스
        // 주석 L25-26)와 동일하게 예외를 흡수하고 로그만 남긴다.
        if (sender == ChatSenderType.COUNSELOR) {
            try {
                notificationService.notify(ticket.getUserId(), NotificationType.COUNSEL_REPLIED,
                        CounselReplyNotificationPayload.serialize(ticketId));
            } catch (Exception e) {
                log.warn("COUNSEL_REPLIED 알림 발행 실패 — ticketId={} exception={}",
                        ticketId, e.getClass().getSimpleName());
            }
        }
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
