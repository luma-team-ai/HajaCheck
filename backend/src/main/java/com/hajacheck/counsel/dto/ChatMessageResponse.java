package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import java.time.LocalDateTime;

/** 상담방 브로드캐스트 메시지 응답(/topic/counsel/{ticketId}). */
public record ChatMessageResponse(
        Long id,
        Long sessionId,
        ChatSenderType sender,
        String content,
        LocalDateTime createdAt) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getSender(),
                message.getContent(),
                message.getCreatedAt());
    }
}
