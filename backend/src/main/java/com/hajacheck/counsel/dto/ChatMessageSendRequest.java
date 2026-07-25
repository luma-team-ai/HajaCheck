package com.hajacheck.counsel.dto;

import jakarta.validation.constraints.Size;

/**
 * STOMP 상담 메시지 전송 페이로드. 텍스트 또는 이미지 첨부(둘 중 최소 하나) — {@code attachmentKey}는
 * 업로드 응답으로 받은 저장키다(서버가 확장자로 MIME 을 재도출하므로 클라이언트 MIME 은 신뢰하지 않는다).
 * "텍스트/첨부 둘 다 비어 있음"은 서비스에서 드롭한다.
 */
public record ChatMessageSendRequest(
        @Size(max = 2000)
        String content,

        @Size(max = 500)
        String attachmentKey) {
}
