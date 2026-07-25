package com.hajacheck.counsel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** STOMP 상담 메시지 전송 페이로드. */
public record ChatMessageSendRequest(
        @NotBlank
        @Size(max = 2000)
        String content) {
}
