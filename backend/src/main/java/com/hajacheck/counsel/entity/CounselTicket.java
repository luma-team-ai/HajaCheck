package com.hajacheck.counsel.entity;

import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.DomainValidationException;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
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

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

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

    /**
     * 대기 티켓을 동일 사용자의 활성 전문상담 세션에 배정한다.
     * 연관관계는 조회 전용으로 유지하되, 검증을 마친 세션의 식별자를 FK 쓰기 값으로 사용한다.
     */
    public void assign(Long counselorId, ChatSession session) {
        requireStatus("assign", CounselTicketStatus.WAITING);
        if (counselorId == null) {
            throw new DomainValidationException("assign 불가: 상담사 식별자는 필수다");
        }
        if (session == null || session.getId() == null) {
            throw new DomainValidationException("assign 불가: 저장된 상담 세션은 필수다");
        }
        if (session.getSessionType() != ChatSessionType.COUNSEL) {
            throw new DomainValidationException("assign 불가: 전문상담 세션만 배정할 수 있다");
        }
        if (session.getEndedAt() != null) {
            throw new DomainStateTransitionException("assign 불가: 종료된 상담 세션은 배정할 수 없다");
        }
        if (!Objects.equals(this.userId, session.getUserId())) {
            throw new DomainValidationException("assign 불가: 티켓 사용자와 상담 세션 사용자가 다르다");
        }
        this.counselorId = counselorId;
        this.sessionId = session.getId();
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
        throw new DomainStateTransitionException(
                "%s 불가: 현재 상담 티켓 상태=%s, 허용 상태=%s"
                        .formatted(action, this.status, Arrays.toString(allowed)));
    }
}
