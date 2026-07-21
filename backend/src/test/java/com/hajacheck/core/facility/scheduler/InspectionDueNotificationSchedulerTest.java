package com.hajacheck.core.facility.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * InspectionDueNotificationScheduler 단위 테스트(NOTI-01, #425). BuiltYearValidatorTest 와 같이
 * 고정 Clock 을 수동 주입하고, 협력자는 Mockito mock 을 직접 생성자 주입한다(@InjectMocks 미사용).
 */
class InspectionDueNotificationSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-07-20T15:00Z = 2026-07-21T00:00 KST → today = 2026-07-21
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-20T15:00:00Z"), KST);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);
    private static final Long OWNER = 100L;

    private FacilityRepository facilityRepository;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;
    private InspectionDueNotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        facilityRepository = mock(FacilityRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = mock(NotificationService.class);
        scheduler = new InspectionDueNotificationScheduler(
                facilityRepository, notificationRepository, notificationService, FIXED);
    }

    private Facility dueFacility(long id, long ownerId, String name) {
        Facility f = mock(Facility.class);
        lenient().when(f.getId()).thenReturn(id);
        lenient().when(f.getOwnerId()).thenReturn(ownerId);
        lenient().when(f.getName()).thenReturn(name);
        lenient().when(f.getNextInspectionDueAt()).thenReturn(TODAY);
        return f;
    }

    private void stubNoExistingNotifications() {
        when(notificationRepository.findAllByUserIdAndTypeAndCreatedAtBetween(
                anyLong(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("오늘 마감 시설물에 INSPECTION_DUE 알림을 발행한다")
    void 오늘마감시설_알림발행() {
        Facility f = dueFacility(1L, OWNER, "시설A");
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any()))
                .thenReturn(List.of(f));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("같은 날 같은 facilityId 알림이 이미 있으면 발행하지 않는다(멱등)")
    void 이미알림존재_스킵() {
        Facility f = dueFacility(1L, OWNER, "시설A");
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any()))
                .thenReturn(List.of(f));
        Notification existing = Notification.create(OWNER, NotificationType.INSPECTION_DUE, "{\"facilityId\":1}");
        when(notificationRepository.findAllByUserIdAndTypeAndCreatedAtBetween(anyLong(), any(), any(), any()))
                .thenReturn(List.of(existing));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("같은 owner의 시설물 2개 중 1개만 이미 알림 있으면 나머지 1개만 발행한다")
    void 일부만알림존재_나머지만발행() {
        Facility f1 = dueFacility(1L, OWNER, "시설1");
        Facility f2 = dueFacility(2L, OWNER, "시설2");
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any()))
                .thenReturn(List.of(f1, f2));
        Notification existing = Notification.create(OWNER, NotificationType.INSPECTION_DUE, "{\"facilityId\":1}");
        when(notificationRepository.findAllByUserIdAndTypeAndCreatedAtBetween(anyLong(), any(), any(), any()))
                .thenReturn(List.of(existing));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(InspectionDueNotificationPayload.extractFacilityId(payloadCaptor.getValue())).isEqualTo(2L);
    }

    @Test
    @DisplayName("한 시설물의 notify가 예외를 던져도 같은 owner의 다음 시설물은 계속 처리한다")
    void notify예외_격리_다음시설계속() {
        Facility f1 = dueFacility(1L, OWNER, "시설1");
        Facility f2 = dueFacility(2L, OWNER, "시설2");
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any()))
                .thenReturn(List.of(f1, f2));
        stubNoExistingNotifications();
        doThrow(new RuntimeException("발행 실패")).doNothing()
                .when(notificationService).notify(anyLong(), any(), anyString());

        scheduler.notifyFacilitiesDueToday();

        // 첫 건이 던져도 두 번째 건까지 시도돼 총 2회 호출된다.
        verify(notificationService, times(2))
                .notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("대상 시설물이 없으면 notify를 전혀 호출하지 않는다")
    void 대상없음_notify미호출() {
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any())).thenReturn(List.of());

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("마감 조회는 주입된 Clock 기준 오늘 날짜로 호출된다")
    void 마감조회_주입Clock기준_오늘날짜() {
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any())).thenReturn(List.of());

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(facilityRepository).findAllByNextInspectionDueAtLessThanEqual(captor.capture());
        assertThat(captor.getValue()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("멱등성 조회 창은 KST 달력일 경계를, createdAt 저장 존(JVM 기본 존)의 LocalDateTime으로 변환해 넘긴다")
    void 멱등성조회창_KST달력일경계_저장존으로변환() {
        // due 시설물이 있어야 owner별 멱등성 조회(findAllByUserIdAndTypeAndCreatedAtBetween)가 실행된다.
        Facility f = dueFacility(1L, OWNER, "시설A");
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any())).thenReturn(List.of(f));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepository).findAllByUserIdAndTypeAndCreatedAtBetween(
                eq(OWNER), eq(NotificationType.INSPECTION_DUE), fromCaptor.capture(), toCaptor.capture());

        // 넘어간 from/to 는 "저장 존(JVM 기본 존)의 벽시계값"이므로, 그 존으로 다시 Instant 환산하면 KST 달력일
        // 경계(오늘 00:00 KST ~ 내일 00:00 KST)의 절대 시각과 정확히 일치해야 한다. 이 검증은 JVM 기본 존과
        // 무관하게 성립하며, 특히 CI/Docker(UTC)처럼 기본 존이 KST가 아닌 곳에서 naive한 today.atStartOfDay()
        // 회귀가 들어오면 즉시 실패한다(2026-07-21T00:00Z ≠ 2026-07-20T15:00Z).
        Instant fromInstant = fromCaptor.getValue().atZone(ZoneId.systemDefault()).toInstant();
        Instant toInstant = toCaptor.getValue().atZone(ZoneId.systemDefault()).toInstant();
        assertThat(fromInstant).isEqualTo(Instant.parse("2026-07-20T15:00:00Z")); // 2026-07-21T00:00 KST
        assertThat(toInstant).isEqualTo(Instant.parse("2026-07-21T15:00:00Z"));   // 2026-07-22T00:00 KST
    }
}
