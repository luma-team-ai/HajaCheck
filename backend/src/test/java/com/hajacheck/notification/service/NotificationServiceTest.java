package com.hajacheck.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hajacheck.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
