package com.hajacheck.notification.entity;

import com.hajacheck.global.util.JsonValidator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 사용자에게 전달되는 서비스 알림. */
@Entity
@Getter
@Table(name = "notifications")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 상태 머신은 아니지만(단순 읽음 플래그 토글), 이 PR의 다른 모든 가변 Entity와의 낙관적 락 일관성을 위해 적용.
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "notification_type", nullable = false)
    private NotificationType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Notification(Long userId, NotificationType type, String payloadJson, boolean read) {
        this.userId = userId;
        this.type = type;
        this.payloadJson = payloadJson;
        this.read = read;
    }

    public static Notification create(Long userId, NotificationType type, String payloadJson) {
        JsonValidator.requireValidJson(payloadJson, "알림 payload(payloadJson)");
        return Notification.builder()
                .userId(userId)
                .type(type)
                .payloadJson(payloadJson)
                .read(false)
                .build();
    }

    public void markAsRead() {
        this.read = true;
    }
}
