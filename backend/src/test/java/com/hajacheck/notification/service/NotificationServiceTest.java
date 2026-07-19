package com.hajacheck.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void markAsRead_이미읽은알림도성공() {
        when(notificationRepository.markAsReadIfUnread(10L, 20L)).thenReturn(0);
        when(notificationRepository.existsByIdAndUserIdAndReadTrue(10L, 20L)).thenReturn(true);

        assertThat(notificationService.markAsRead(10L, 20L)).isTrue();
    }

    @Test
    void getNotifications_읽음미읽음모두포함_DTO로변환하여반환() {
        Notification unread = Notification.create(20L, NotificationType.ANALYSIS_DONE, "{\"inspectionId\":1}");
        Notification read = Notification.create(20L, NotificationType.REVIEW_PENDING, null);
        read.markAsRead();
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of(unread, read));

        List<NotificationResponse> result = notificationService.getNotifications(20L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("ANALYSIS_DONE");
        assertThat(result.get(0).payload().get("inspectionId").asInt()).isEqualTo(1);
        assertThat(result.get(0).read()).isFalse();
        assertThat(result.get(1).type()).isEqualTo("REVIEW_PENDING");
        assertThat(result.get(1).payload()).isNull();
        assertThat(result.get(1).read()).isTrue();
    }

    @Test
    void getNotifications_알림없음_빈목록반환() {
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(notificationService.getNotifications(20L)).isEmpty();
    }
}
