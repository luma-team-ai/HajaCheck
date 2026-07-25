package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import java.time.LocalDateTime;

/** 상담원 대기열/내 이력 목록 항목 — 티켓 요약(대화 내용 등 민감 정보는 제외, 배정 상담원 식별 정보는 포함). */
public record CounselTicketSummaryResponse(
        Long id,
        String ticketNumber,
        String category,
        String title,
        Long userId,
        Long counselorId,
        String counselorName,
        CounselTicketStatus status,
        Integer queuePosition,
        LocalDateTime createdAt) {

    /**
     * @param counselorName 배정된 상담원 표시 이름(미배정·탈퇴 등이면 null). 목록 조회는 호출부가 페이지 내
     *                      counselorId 들을 배치로 모아 이름을 해석해 전달한다(N+1 방지).
     */
    public static CounselTicketSummaryResponse from(CounselTicket ticket, String counselorName) {
        return new CounselTicketSummaryResponse(
                ticket.getId(),
                ticket.getTicketNumber(),
                ticket.getCategory(),
                ticket.getTitle(),
                ticket.getUserId(),
                ticket.getCounselorId(),
                counselorName,
                ticket.getStatus(),
                ticket.getQueuePosition(),
                ticket.getCreatedAt());
    }
}
