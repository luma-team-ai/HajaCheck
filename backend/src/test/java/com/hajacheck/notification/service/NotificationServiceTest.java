package com.hajacheck.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void markAsRead_미읽음알림_읽음처리성공() {
        when(notificationRepository.markAsReadIfUnread(10L, 20L)).thenReturn(1);

        notificationService.markAsRead(10L, 20L);

        verify(notificationRepository).markAsReadIfUnread(10L, 20L);
    }

    @Test
    void markAsRead_이미읽은알림_멱등_예외없음() {
        when(notificationRepository.markAsReadIfUnread(10L, 20L)).thenReturn(0);
        when(notificationRepository.existsByIdAndUserIdAndReadTrue(10L, 20L)).thenReturn(true);

        notificationService.markAsRead(10L, 20L);

        verify(notificationRepository).existsByIdAndUserIdAndReadTrue(10L, 20L);
    }

    @Test
    void markAsRead_없는알림또는타인소유_NOTIFICATION_NOT_FOUND() {
        when(notificationRepository.markAsReadIfUnread(10L, 20L)).thenReturn(0);
        when(notificationRepository.existsByIdAndUserIdAndReadTrue(10L, 20L)).thenReturn(false);

        assertThatThrownBy(() -> notificationService.markAsRead(10L, 20L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getNotifications_읽음미읽음모두포함_DTO로변환하여반환() {
        Notification unread = Notification.create(20L, NotificationType.ANALYSIS_DONE, "{\"inspectionId\":1}");
        Notification read = Notification.create(20L, NotificationType.REVIEW_PENDING, null);
        read.markAsRead();
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), any(Pageable.class)))
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
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(notificationService.getNotifications(20L)).isEmpty();
    }

    /**
     * 리뷰 P2-2: any(Pageable.class) 스텁만으로는 실제 페이지 크기(상위 30건)를 검증하지 못한다
     * — repository에 정확히 PageRequest.of(0, 30)이 전달되는지 직접 고정한다.
     */
    @Test
    void getNotifications_페이지요청은0페이지_30건상한() {
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of());

        notificationService.getNotifications(20L);

        verify(notificationRepository)
                .findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), eq(PageRequest.of(0, 30)));
    }

    @Test
    void notify_알림생성_repository_save에_위임() {
        notificationService.notify(20L, NotificationType.INSPECTION_DUE, "{\"facilityId\":7}");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(20L);
        assertThat(saved.getType()).isEqualTo(NotificationType.INSPECTION_DUE);
        assertThat(saved.getPayloadJson()).isEqualTo("{\"facilityId\":7}");
        assertThat(saved.isRead()).isFalse();
    }

    @Test
    void notify_잘못된JSON_payload_DomainValidationException_전파() {
        // Notification.create 의 JSON 검증 실패가 서비스에서 삼켜지지 않고 그대로 전파돼야 한다.
        assertThatThrownBy(() ->
                notificationService.notify(20L, NotificationType.INSPECTION_DUE, "{invalid json"))
                .isInstanceOf(DomainValidationException.class);

        verify(notificationRepository, never()).save(any());
    }
}
