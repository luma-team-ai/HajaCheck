package com.hajacheck.counsel.dto;

import jakarta.validation.constraints.Size;

/** 상담원 전용 비공개 메모 저장(upsert) 요청 — 빈 메모(null/blank)도 허용한다(초기화 용도). */
public record CounselTicketNoteUpdateRequest(
        @Size(max = 4000)
        String content) {
}
