package com.hajacheck.counsel.controller;

import com.hajacheck.counsel.dto.ChatMessageSendRequest;
import com.hajacheck.counsel.service.CounselChatService;
import com.hajacheck.counsel.websocket.StompUserPrincipal;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * 상담방 STOMP 진입점(FR-7, #20/HAJA-33) — {@code SEND /app/counsel/{ticketId}/send}. 발신자는
 * 핸드셰이크/CONNECT 에서 검증된 {@link StompUserPrincipal} 로 식별하며(클라이언트 페이로드의 senderId 를
 * 신뢰하지 않음), 실제 참여자·상태 검증과 브로드캐스트는 {@link CounselChatService} 가 담당한다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class CounselChatController {

    private final CounselChatService counselChatService;

    @MessageMapping("/counsel/{ticketId}/send")
    public void sendMessage(@DestinationVariable Long ticketId,
                            @Valid @Payload ChatMessageSendRequest request,
                            Principal principal) {
        Long senderUserId = resolveUserId(principal);
        if (senderUserId == null) {
            log.debug("상담 메시지 드롭 — 인증 주체 없음: ticketId={}", ticketId);
            return;
        }
        counselChatService.sendMessage(ticketId, senderUserId, request.content(), request.attachmentKey());
    }

    private Long resolveUserId(Principal principal) {
        if (principal instanceof StompUserPrincipal user) {
            return user.getUserId();
        }
        return null;
    }
}
