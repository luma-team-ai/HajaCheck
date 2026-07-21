package com.hajacheck.core.facility.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
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
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
        return dueFacility(id, ownerId, name, TODAY);
    }

    private Facility dueFacility(long id, long ownerId, String name, LocalDate dueAt) {
        Facility f = mock(Facility.class);
        lenient().when(f.getId()).thenReturn(id);
        lenient().when(f.getOwnerId()).thenReturn(ownerId);
        lenient().when(f.getName()).thenReturn(name);
        lenient().when(f.getNextInspectionDueAt()).thenReturn(dueAt);
        return f;
    }

    private Page<Facility> singlePage(List<Facility> content) {
        return new PageImpl<>(content);
    }

    private void stubDuePage(List<Facility> content) {
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any(), any()))
                .thenReturn(singlePage(content));
    }

    private void stubNoExistingNotifications() {
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any())).thenReturn(List.of());
    }

    /** 시설물 자신을 직렬화한 payload로 "이미 발행된 알림"을 만든다(도래일 포함 dedupe 키가 정확히 일치). */
    private Notification existingNotificationFor(Facility facility) {
        return Notification.create(OWNER, NotificationType.INSPECTION_DUE,
                InspectionDueNotificationPayload.serialize(facility));
    }

    @Test
    @DisplayName("오늘 마감 시설물에 INSPECTION_DUE 알림을 발행한다")
    void 오늘마감시설_알림발행() {
        stubDuePage(List.of(dueFacility(1L, OWNER, "시설A")));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("현재 도래일로 이미 발행된 알림이 있으면 발행하지 않는다(멱등)")
    void 이미알림존재_스킵() {
        Facility f = dueFacility(1L, OWNER, "시설A");
        stubDuePage(List.of(f));
        Notification existing = existingNotificationFor(f);
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any()))
                .thenReturn(List.of(existing));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("같은 owner의 시설물 2개 중 1개만 이미 알림 있으면 나머지 1개만 발행한다")
    void 일부만알림존재_나머지만발행() {
        Facility f1 = dueFacility(1L, OWNER, "시설1");
        Facility f2 = dueFacility(2L, OWNER, "시설2");
        stubDuePage(List.of(f1, f2));
        Notification existingForF1 = existingNotificationFor(f1);
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any()))
                .thenReturn(List.of(existingForF1));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(InspectionDueNotificationPayload.extractFacilityId(payloadCaptor.getValue())).isEqualTo(2L);
    }

    @Test
    @DisplayName("한 시설물의 notify가 예외를 던져도 같은 owner의 다음 시설물은 계속 처리한다")
    void notify예외_격리_다음시설계속() {
        stubDuePage(List.of(dueFacility(1L, OWNER, "시설1"), dueFacility(2L, OWNER, "시설2")));
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
        stubDuePage(List.of());

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("마감 조회는 주입된 Clock 기준 오늘 날짜로 호출된다")
    void 마감조회_주입Clock기준_오늘날짜() {
        stubDuePage(List.of());

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(facilityRepository).findAllByNextInspectionDueAtLessThanEqual(captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("도래일이 그대로인 overdue 시설물은 재실행해도 두 번째엔 스킵된다(스팸 방지)")
    void overdue_도래일불변_재실행시_스킵() {
        // overdue(어제 마감) 시설물 — 도래일 값은 재스케줄 전까지 바뀌지 않는다.
        Facility f = dueFacility(1L, OWNER, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));

        // 1회차: 기존 알림 없음 → 발행
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any())).thenReturn(List.of());
        scheduler.notifyFacilitiesDueToday();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());

        // 2회차(재실행): 1회차에 발행된 알림이 이미 존재(도래일 불변) → 재발행 없음
        Notification firstRun = Notification.create(OWNER, NotificationType.INSPECTION_DUE, payloadCaptor.getValue());
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any())).thenReturn(List.of(firstRun));
        scheduler.notifyFacilitiesDueToday();

        // 총 발행은 여전히 1회 — overdue라고 매일 재알림되지 않는다.
        verify(notificationService, times(1)).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("owner가 3명이어도 기존 알림 조회는 정확히 1회만 호출된다(N+1 방지)")
    void owner3명_기존알림조회_1회만() {
        stubDuePage(List.of(
                dueFacility(1L, 100L, "A시설"),
                dueFacility(2L, 200L, "B시설"),
                dueFacility(3L, 300L, "C시설")));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(notificationRepository, times(1))
                .findAllByUserIdInAndType(anySet(), eq(NotificationType.INSPECTION_DUE));
        verify(notificationService, times(3))
                .notify(anyLong(), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("결과가 여러 페이지에 걸쳐도 모든 페이지가 처리된다")
    void 여러페이지_전부처리() {
        Facility p0 = dueFacility(1L, OWNER, "1페이지시설");
        Facility p1 = dueFacility(2L, OWNER, "2페이지시설");
        // pageSize=1, total=2 → page0.hasNext()=true, page1.hasNext()=false 로 강제.
        Page<Facility> page0 = new PageImpl<>(List.of(p0), PageRequest.of(0, 1), 2);
        Page<Facility> page1 = new PageImpl<>(List.of(p1), PageRequest.of(1, 1), 2);
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqual(any(), any()))
                .thenReturn(page0, page1);
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(facilityRepository, times(2)).findAllByNextInspectionDueAtLessThanEqual(any(), any());
        verify(notificationService, times(2))
                .notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("기존 알림 배치 조회가 실패하면 그 페이지는 스킵하고 발행하지 않는다")
    void 배치조회실패_페이지스킵() {
        stubDuePage(List.of(dueFacility(1L, OWNER, "시설A")));
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any()))
                .thenThrow(new RuntimeException("DB 오류"));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }
}
