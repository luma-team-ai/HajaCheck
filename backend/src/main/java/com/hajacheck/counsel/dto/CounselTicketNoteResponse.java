package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.CounselTicketNote;
import java.time.LocalDateTime;

/** 상담원 전용 비공개 메모 응답(고객 비노출, #1021/HAJA-503). */
public record CounselTicketNoteResponse(
        Long ticketId,
        Long counselorId,
        String content,
        LocalDateTime updatedAt) {

    /** 메모가 아직 없는 티켓(신규) — content=null, updatedAt=null 로 "빈 메모"를 표현한다. */
    public static CounselTicketNoteResponse empty(Long ticketId) {
        return new CounselTicketNoteResponse(ticketId, null, null, null);
    }

    public static CounselTicketNoteResponse from(CounselTicketNote note) {
        return new CounselTicketNoteResponse(
                note.getTicketId(),
                note.getCounselorId(),
                note.getContent(),
                note.getUpdatedAt());
    }
}
