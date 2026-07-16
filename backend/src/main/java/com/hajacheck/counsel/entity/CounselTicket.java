package com.hajacheck.counsel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 사용자의 전문 상담 요청과 상담 진행 상태.
 *
 * <p>{@code sessionId}는 FK 값 컬럼을 실제 매핑 소스로 두고, 연관관계({@code session})는 조회 전용
 * ({@code insertable/updatable = false})으로 병행 제공한다. {@code userId}/{@code counselorId}는
 * auth 도메인 경계를 넘는 참조라 Long 값만 보유한다.</p>
 */
@Entity
@Getter
@Table(name = "counsel_tickets", indexes = {
        @Index(name = "idx_counsel_tickets_counselor", columnList = "counselor_id"),
        @Index(name = "idx_counsel_tickets_user", columnList = "user_id"),
        @Index(name = "idx_counsel_tickets_session", columnList = "session_id")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CounselTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "counselor_id")
    private Long counselorId;

    @Column(name = "session_id")
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", insertable = false, updatable = false)
    private ChatSession session;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "counsel_ticket_status_type", nullable = false)
    private CounselTicketStatus status;

    @Column(name = "queue_position")
    private Integer queuePosition;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private CounselTicket(Long userId, Long counselorId, Long sessionId,
                          CounselTicketStatus status, Integer queuePosition, Instant endedAt) {
        this.userId = userId;
        this.counselorId = counselorId;
        this.sessionId = sessionId;
        this.status = status == null ? CounselTicketStatus.WAITING : status;
        this.queuePosition = queuePosition;
        this.endedAt = endedAt;
    }

    public static CounselTicket request(Long userId, Integer queuePosition) {
        return CounselTicket.builder()
                .userId(userId)
                .status(CounselTicketStatus.WAITING)
                .queuePosition(queuePosition)
                .build();
    }

    public void assign(Long counselorId, Long sessionId) {
        requireStatus("assign", CounselTicketStatus.WAITING);
        this.counselorId = counselorId;
        this.sessionId = sessionId;
        this.status = CounselTicketStatus.IN_PROGRESS;
        this.queuePosition = null;
    }

    public void resolve() {
        requireStatus("resolve", CounselTicketStatus.IN_PROGRESS);
        this.status = CounselTicketStatus.RESOLVED;
        this.endedAt = Instant.now();
    }

    public void leaveOffline() {
        requireStatus("leaveOffline", CounselTicketStatus.WAITING, CounselTicketStatus.IN_PROGRESS);
        this.status = CounselTicketStatus.OFFLINE_LEFT;
        this.endedAt = Instant.now();
    }

    private void requireStatus(String action, CounselTicketStatus... allowed) {
        for (CounselTicketStatus candidate : allowed) {
            if (this.status == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
                "%s 불가: 현재 상담 티켓 상태=%s, 허용 상태=%s"
                        .formatted(action, this.status, Arrays.toString(allowed)));
    }
}
