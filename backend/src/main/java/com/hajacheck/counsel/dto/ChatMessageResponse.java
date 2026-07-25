package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import java.time.LocalDateTime;

/**
 * 상담방 브로드캐스트/이력 메시지 응답. 첨부가 있으면 원본 저장키 대신 인가된 서빙 API 경로
 * ({@code attachmentUrl})만 노출한다(정적 URL 미노출 — Media 썸네일과 동일 원칙).
 */
public record ChatMessageResponse(
        Long id,
        Long sessionId,
        ChatSenderType sender,
        String content,
        String attachmentUrl,
        LocalDateTime createdAt) {

    public static ChatMessageResponse from(ChatMessage message, Long ticketId) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getSender(),
                message.getContent(),
                attachmentUrl(message, ticketId),
                message.getCreatedAt());
    }

    private static String attachmentUrl(ChatMessage message, Long ticketId) {
        if (message.getAttachmentKey() == null) {
            return null;
        }
        return "/api/counsel/tickets/%d/messages/%d/attachment".formatted(ticketId, message.getId());
    }
}
