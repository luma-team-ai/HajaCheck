package com.hajacheck.counsel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 사용자별 AI·시나리오 봇·전문 상담 채팅 세션. */
@Entity
@Getter
@Table(name = "chat_sessions", indexes = {
        @Index(name = "idx_chat_sessions_user", columnList = "user_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "session_type", columnDefinition = "chat_session_type", nullable = false)
    private ChatSessionType sessionType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private ChatSession(Long userId, ChatSessionType sessionType, Instant startedAt, Instant endedAt) {
        this.userId = userId;
        this.sessionType = sessionType;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public static ChatSession start(Long userId, ChatSessionType sessionType) {
        return ChatSession.builder()
                .userId(userId)
                .sessionType(sessionType)
                .startedAt(Instant.now())
                .build();
    }

    public void end() {
        if (this.endedAt != null) {
            return;
        }
        this.endedAt = Instant.now();
    }
}
