package com.hajacheck.counsel.dto;

/**
 * 채팅 이미지 업로드 응답 — 저장키와 MIME. 프론트는 {@code attachmentKey}를 WS SEND 메시지 바디에 실어
 * 보낸다(서버가 저장키 확장자로 MIME 을 재도출하므로 mimeType 은 표시용 참고값).
 */
public record CounselAttachmentResponse(
        String attachmentKey,
        String mimeType) {
}
