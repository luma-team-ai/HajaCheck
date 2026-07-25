package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import java.time.LocalDateTime;

/** 상담원 대기열 목록 항목 — 티켓 요약(민감 정보 제외). */
public record CounselTicketSummaryResponse(
        Long id,
        String ticketNumber,
        String category,
        String title,
        Long userId,
        CounselTicketStatus status,
        Integer queuePosition,
        LocalDateTime createdAt) {

    public static CounselTicketSummaryResponse from(CounselTicket ticket) {
        return new CounselTicketSummaryResponse(
                ticket.getId(),
                ticket.getTicketNumber(),
                ticket.getCategory(),
                ticket.getTitle(),
                ticket.getUserId(),
                ticket.getStatus(),
                ticket.getQueuePosition(),
                ticket.getCreatedAt());
    }
}
