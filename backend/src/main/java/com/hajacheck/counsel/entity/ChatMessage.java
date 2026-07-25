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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 채팅 세션에서 송수신된 메시지.
 *
 * <p>{@code sessionId}/{@code scenarioId}는 FK 값 컬럼을 실제 매핑 소스로 두고, 연관관계
 * ({@code session}/{@code scenario})는 조회 전용({@code insertable/updatable = false})으로 병행 제공한다.</p>
 */
@Entity
@Getter
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_messages_session", columnList = "session_id"),
        @Index(name = "idx_chat_messages_scenario", columnList = "scenario_id")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", insertable = false, updatable = false)
    private ChatSession session;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "chat_sender_type", nullable = false)
    private ChatSenderType sender;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "scenario_id")
    private Long scenarioId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", insertable = false, updatable = false)
    private BotScenario scenario;

    // 이미지 첨부 — FileStorageService storageKey(실제 URL 아님) + MIME. 텍스트만 있는 메시지는 둘 다 null.
    @Column(name = "attachment_key", length = 500)
    private String attachmentKey;

    @Column(name = "attachment_mime_type", length = 100)
    private String attachmentMimeType;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private ChatMessage(Long sessionId, ChatSenderType sender, String content, Long scenarioId,
                        String attachmentKey, String attachmentMimeType) {
        this.sessionId = sessionId;
        this.sender = sender;
        this.content = content;
        this.scenarioId = scenarioId;
        this.attachmentKey = attachmentKey;
        this.attachmentMimeType = attachmentMimeType;
    }

    public static ChatMessage create(Long sessionId, ChatSenderType sender, String content,
                                     Long scenarioId, String attachmentKey, String attachmentMimeType) {
        return ChatMessage.builder()
                .sessionId(sessionId)
                .sender(sender)
                .content(content)
                .scenarioId(scenarioId)
                .attachmentKey(attachmentKey)
                .attachmentMimeType(attachmentMimeType)
                .build();
    }

    /** 텍스트 전용 메시지 편의 팩토리(첨부 없음). */
    public static ChatMessage createText(Long sessionId, ChatSenderType sender, String content) {
        return create(sessionId, sender, content, null, null, null);
    }
}
