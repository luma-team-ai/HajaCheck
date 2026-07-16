package com.hajacheck.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationTest {

    @Test
    void create_읽지않은알림과JsonPayload를생성() {
        Notification notification = Notification.create(
                10L, NotificationType.ANALYSIS_DONE, "{\"inspectionId\":20}");

        assertThat(notification.getUserId()).isEqualTo(10L);
        assertThat(notification.getType()).isEqualTo(NotificationType.ANALYSIS_DONE);
        assertThat(notification.getPayloadJson()).isEqualTo("{\"inspectionId\":20}");
        assertThat(notification.isRead()).isFalse();
    }

    @Test
    void markAsRead_읽음상태를명시적으로변경() {
        Notification notification = Notification.create(10L, NotificationType.REVIEW_PENDING, null);

        notification.markAsRead();

        assertThat(notification.isRead()).isTrue();
    }
}
