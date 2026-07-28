package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import java.time.LocalDateTime;

/**
 * 상담원 대기열/내 이력 목록 항목 — 티켓 요약(대화 내용 등 민감 정보는 제외, 배정 상담원 식별 정보는 포함).
 *
 * <p>{@code customerName}/{@code customerEmail}/{@code customerPlan}/{@code customerJoinedAt}(#1168) 은
 * 플랫폼 관리자의 날짜별 상담 목록({@code getAdminTicketsByDate}) 전용 optional 필드다. 기존 사용처
 * (상담원 대기열/내 이력, {@link #from})는 채우지 않아도 되므로 하위 호환이며 항상 null 이다.
 */
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
        LocalDateTime createdAt,
        String customerName,
        String customerEmail,
        String customerPlan,
        LocalDateTime customerJoinedAt) {

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
                ticket.getCreatedAt(),
                null,
                null,
                null,
                null);
    }

    /**
     * 플랫폼 관리자 날짜별 목록 전용(#1168) — 고객 프로필(이름/이메일/활성 플랜명/가입일)까지 채운다.
     * 호출부가 페이지 내 userId 들을 배치로 모아 조회해 전달한다(N+1 방지, {@code resolveCounselorNames}와 동일 패턴).
     */
    public static CounselTicketSummaryResponse fromAdmin(
            CounselTicket ticket,
            String counselorName,
            String customerName,
            String customerEmail,
            String customerPlan,
            LocalDateTime customerJoinedAt) {
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
                ticket.getCreatedAt(),
                customerName,
                customerEmail,
                customerPlan,
                customerJoinedAt);
    }
}
