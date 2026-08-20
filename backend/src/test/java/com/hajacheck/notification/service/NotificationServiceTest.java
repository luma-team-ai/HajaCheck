package com.hajacheck.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.inspection.repository.InspectionRoundNoProjection;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import java.util.List;
import java.util.Set;
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
    // #1706 — 회차 표기를 조회 시점에 다시 계산하기 위한 배치 조회 의존성.
    @Mock
    private InspectionRepository inspectionRepository;

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
    void delete_본인알림_삭제성공() {
        when(notificationRepository.deleteByIdAndUserId(10L, 20L)).thenReturn(1);

        notificationService.delete(10L, 20L);

        verify(notificationRepository).deleteByIdAndUserId(10L, 20L);
    }

    @Test
    void delete_없는알림또는타인소유_NOTIFICATION_NOT_FOUND() {
        when(notificationRepository.deleteByIdAndUserId(10L, 20L)).thenReturn(0);

        assertThatThrownBy(() -> notificationService.delete(10L, 20L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getNotifications_읽음미읽음모두포함_DTO로변환하여반환() {
        Notification unread = Notification.create(20L, NotificationType.ANALYSIS_DONE, "{\"inspectionId\":1}");
        Notification read = Notification.create(20L, NotificationType.REVIEW_PENDING, null);
        read.markAsRead();
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of(unread, read));
        when(inspectionRepository.findRoundNosByIds(Set.of(1L))).thenReturn(List.of());

        List<NotificationResponse> result = notificationService.getNotifications(20L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("ANALYSIS_DONE");
        assertThat(result.get(0).payload().get("inspectionId").asInt()).isEqualTo(1);
        assertThat(result.get(0).read()).isFalse();
        assertThat(result.get(1).type()).isEqualTo("REVIEW_PENDING");
        assertThat(result.get(1).payload()).isNull();
        assertThat(result.get(1).read()).isTrue();
    }

    /**
     * #1706 — 저장된 payload의 "{roundNo}회차"는 #1702 재정렬로 stale해질 수 있어, 목록 조회 시점에
     * 현재 회차로 다시 계산해 덮어쓴다. 목록 경로라 알림 건별 단건 조회(N+1)가 아니라 대상 점검을
     * 한 번에 모아 배치 조회하는지도 함께 고정한다.
     */
    @Test
    void getNotifications_회차표기는_현재회차로재계산되고_배치조회는1회다() {
        Notification analysisDone = Notification.create(20L, NotificationType.ANALYSIS_DONE,
                "{\"inspectionId\":7,\"description\":\"2회차\"}");
        Notification reviewPending = Notification.create(20L, NotificationType.REVIEW_PENDING,
                "{\"inspectionId\":8,\"description\":\"5회차\"}");
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of(analysisDone, reviewPending));
        when(inspectionRepository.findRoundNosByIds(Set.of(7L, 8L)))
                .thenReturn(List.of(roundNoProjection(7L, 3), roundNoProjection(8L, 6)));

        List<NotificationResponse> result = notificationService.getNotifications(20L);

        assertThat(result.get(0).payload().get("description").asText()).isEqualTo("3회차");
        assertThat(result.get(1).payload().get("description").asText()).isEqualTo("6회차");
        // 알림 2건이지만 점검 조회는 단 한 번(IN 절 배치) — N+1 회귀선.
        verify(inspectionRepository, times(1)).findRoundNosByIds(any());
    }

    @Test
    void getNotifications_대상점검이사라졌으면_저장된회차표기를유지한다() {
        Notification orphan = Notification.create(20L, NotificationType.ANALYSIS_DONE,
                "{\"inspectionId\":99,\"description\":\"4회차\"}");
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of(orphan));
        when(inspectionRepository.findRoundNosByIds(Set.of(99L))).thenReturn(List.of());

        List<NotificationResponse> result = notificationService.getNotifications(20L);

        assertThat(result.get(0).payload().get("description").asText()).isEqualTo("4회차");
    }

    @Test
    void getNotifications_회차없는알림유형은_점검조회도_payload변경도없다() {
        Notification due = Notification.create(20L, NotificationType.INSPECTION_DUE,
                "{\"facilityId\":3,\"description\":\"점검 예정일이 다가왔습니다\"}");
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(eq(20L), any(Pageable.class)))
                .thenReturn(List.of(due));

        List<NotificationResponse> result = notificationService.getNotifications(20L);

        assertThat(result.get(0).payload().get("description").asText())
                .isEqualTo("점검 예정일이 다가왔습니다");
        verify(inspectionRepository, never()).findRoundNosByIds(any());
    }

    private static InspectionRoundNoProjection roundNoProjection(Long id, Integer roundNo) {
        return new InspectionRoundNoProjection() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Integer getRoundNo() {
                return roundNo;
            }
        };
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
