package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.ChatSenderType;

/**
 * 상담방 "입력 중" 알림 브로드캐스트(#1000/#1001 후속) — {@code /topic/counsel/{ticketId}/typing}.
 * 영속화하지 않는 휘발성 신호라 {@code ChatMessageResponse}와 달리 id/createdAt이 없다.
 */
public record TypingIndicatorResponse(ChatSenderType sender) {
}
