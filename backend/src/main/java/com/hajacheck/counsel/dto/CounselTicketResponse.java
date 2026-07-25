package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import java.time.Instant;
import java.time.LocalDateTime;

/** 상담 티켓 단건 응답 — 생성/배정/종료 결과 및 개인 알림 페이로드. */
public record CounselTicketResponse(
        Long id,
        Long userId,
        Long counselorId,
        Long sessionId,
        CounselTicketStatus status,
        Integer queuePosition,
        LocalDateTime createdAt,
        Instant endedAt) {

    public static CounselTicketResponse from(CounselTicket ticket) {
        return new CounselTicketResponse(
                ticket.getId(),
                ticket.getUserId(),
                ticket.getCounselorId(),
                ticket.getSessionId(),
                ticket.getStatus(),
                ticket.getQueuePosition(),
                ticket.getCreatedAt(),
                ticket.getEndedAt());
    }
}
