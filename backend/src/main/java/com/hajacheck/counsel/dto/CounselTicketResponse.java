package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import java.time.Instant;
import java.time.LocalDateTime;

/** 상담 티켓 단건 응답 — 생성/배정/종료 결과 및 개인 알림 페이로드. */
public record CounselTicketResponse(
        Long id,
        String ticketNumber,
        String category,
        String title,
        Long userId,
        Long counselorId,
        String counselorName,
        Long sessionId,
        CounselTicketStatus status,
        Integer queuePosition,
        LocalDateTime createdAt,
        Instant endedAt) {

    /**
     * @param counselorName 배정된 상담원 표시 이름(미배정·탈퇴 등이면 null). 이름 조회는 호출부(서비스)가
     *                      {@code counselorId} 로 수행해 전달한다 — 엔티티만으로는 알 수 없기 때문.
     */
    public static CounselTicketResponse from(CounselTicket ticket, String counselorName) {
        return new CounselTicketResponse(
                ticket.getId(),
                ticket.getTicketNumber(),
                ticket.getCategory(),
                ticket.getTitle(),
                ticket.getUserId(),
                ticket.getCounselorId(),
                counselorName,
                ticket.getSessionId(),
                ticket.getStatus(),
                ticket.getQueuePosition(),
                ticket.getCreatedAt(),
                ticket.getEndedAt());
    }
}
