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

import com.hajacheck.auth.service.CompanyOwnerLookupService;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.InspectionNotificationSetting;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.facility.repository.InspectionNotificationSettingRepository;
import com.hajacheck.core.facility.scheduler.InspectionDueNotificationPayload.Kind;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/**
 * InspectionDueNotificationScheduler 단위 테스트(NOTI-01, #425 / 알림설정 게이팅, #540 ③). BuiltYearValidatorTest
 * 와 같이 고정 Clock 을 수동 주입하고, 협력자는 Mockito mock 을 직접 생성자 주입한다(@InjectMocks 미사용).
 */
class InspectionDueNotificationSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-07-20T15:00Z = 2026-07-21T00:00 KST → today = 2026-07-21
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-20T15:00:00Z"), KST);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);
    private static final Long OWNER = 100L;
    private static final Long COMPANY = 900L;

    private FacilityRepository facilityRepository;
    private InspectionNotificationSettingRepository notificationSettingRepository;
    private CompanyOwnerLookupService companyOwnerLookupService;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;
    private InspectionDueNotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        facilityRepository = mock(FacilityRepository.class);
        notificationSettingRepository = mock(InspectionNotificationSettingRepository.class);
        companyOwnerLookupService = mock(CompanyOwnerLookupService.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = mock(NotificationService.class);
        lenient().when(companyOwnerLookupService.findOwnerUserIds(any()))
                .thenAnswer(invocation -> ((Collection<Long>) invocation.getArgument(0)).stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                companyId -> companyId.equals(COMPANY) ? OWNER : companyId)));
        // 대부분의 테스트는 알림설정 행이 없는(=기본값 적용) 시나리오라 기본 스텁으로 빈 리스트를 둔다.
        lenient().when(notificationSettingRepository.findAllByUserIdInAndFacilityIdIn(any(), any()))
                .thenReturn(List.of());
        scheduler = new InspectionDueNotificationScheduler(
                facilityRepository, notificationSettingRepository, companyOwnerLookupService,
                notificationRepository, notificationService, FIXED);
    }

    private Facility dueFacility(long id, long ownerId, String name) {
        return dueFacility(id, ownerId, name, TODAY);
    }

    private Facility dueFacility(long id, long ownerId, String name, LocalDate dueAt) {
        Facility f = mock(Facility.class);
        lenient().when(f.getId()).thenReturn(id);
        lenient().when(f.getCompanyId()).thenReturn(ownerId);
        lenient().when(f.getName()).thenReturn(name);
        lenient().when(f.getNextInspectionDueAt()).thenReturn(dueAt);
        return f;
    }

    private InspectionNotificationSetting settingRow(Long userId, Long facilityId,
            boolean notifyBeforeEnabled, int notifyBeforeDays, boolean warnOnOverdueEnabled) {
        InspectionNotificationSetting setting = mock(InspectionNotificationSetting.class);
        lenient().when(setting.getUserId()).thenReturn(userId);
        lenient().when(setting.getFacilityId()).thenReturn(facilityId);
        lenient().when(setting.isNotifyBeforeEnabled()).thenReturn(notifyBeforeEnabled);
        lenient().when(setting.getNotifyBeforeDays()).thenReturn((short) notifyBeforeDays);
        lenient().when(setting.isWarnOnOverdueEnabled()).thenReturn(warnOnOverdueEnabled);
        return setting;
    }

    private Slice<Facility> singlePage(List<Facility> content) {
        return new SliceImpl<>(content);
    }

    private void stubDuePage(List<Facility> content) {
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(any(), any()))
                .thenReturn(singlePage(content));
    }

    private void stubNoExistingNotifications() {
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any())).thenReturn(List.of());
    }

    private void stubSettings(InspectionNotificationSetting... settings) {
        when(notificationSettingRepository.findAllByUserIdInAndFacilityIdIn(any(), any()))
                .thenReturn(List.of(settings));
    }

    /** 시설물 자신을 직렬화한 payload로 "이미 발행된 알림"을 만든다(도래일 포함 dedupe 키가 정확히 일치). */
    private Notification existingNotificationFor(Facility facility, Kind kind) {
        return Notification.create(OWNER, NotificationType.INSPECTION_DUE,
                InspectionDueNotificationPayload.serialize(facility, kind));
    }

    @Test
    @DisplayName("오늘 마감 시설물에 INSPECTION_DUE 알림을 발행한다")
    void 오늘마감시설_알림발행() {
        stubDuePage(List.of(dueFacility(1L, COMPANY, "시설A")));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("현재 도래일로 이미 발행된 알림이 있으면 발행하지 않는다(멱등)")
    void 이미알림존재_스킵() {
        Facility f = dueFacility(1L, COMPANY, "시설A");
        stubDuePage(List.of(f));
        Notification existing = existingNotificationFor(f, Kind.DUE);
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any()))
                .thenReturn(List.of(existing));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("같은 owner의 시설물 2개 중 1개만 이미 알림 있으면 나머지 1개만 발행한다")
    void 일부만알림존재_나머지만발행() {
        Facility f1 = dueFacility(1L, COMPANY, "시설1");
        Facility f2 = dueFacility(2L, COMPANY, "시설2");
        stubDuePage(List.of(f1, f2));
        Notification existingForF1 = existingNotificationFor(f1, Kind.DUE);
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
        stubDuePage(List.of(dueFacility(1L, COMPANY, "시설1"), dueFacility(2L, COMPANY, "시설2")));
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
    @DisplayName("마감 조회는 주입된 Clock 기준 오늘+365일(사전알림 최대 창) 상한으로 호출된다")
    void 마감조회_주입Clock기준_오늘_플러스최대창_상한() {
        stubDuePage(List.of());

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(facilityRepository).findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(TODAY.plusDays(365));
    }

    @Test
    @DisplayName("경과알림 설정이 켜진 overdue 시설물은 도래일이 그대로면 재실행해도 두 번째엔 스킵된다(스팸 방지)")
    void overdue_경과알림설정켜짐_도래일불변_재실행시_스킵() {
        // overdue(어제 마감) 시설물 — 도래일 값은 재스케줄 전까지 바뀌지 않는다. warnOnOverdueEnabled=true로
        // 명시 설정해야 #540 ③ 게이팅상 OVERDUE 알림이 발행된다(기본값은 false — 별도 테스트로 커버).
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 7, true));

        // 1회차: 기존 알림 없음 → 발행
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any())).thenReturn(List.of());
        scheduler.notifyFacilitiesDueToday();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"OVERDUE\"");

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
        Facility p0 = dueFacility(1L, COMPANY, "1페이지시설");
        Facility p1 = dueFacility(2L, COMPANY, "2페이지시설");
        // pageSize=1 → page0.hasNext()=true(다음 페이지 있음), page1.hasNext()=false(마지막) 로 강제.
        Slice<Facility> page0 = new SliceImpl<>(List.of(p0), PageRequest.of(0, 1), true);
        Slice<Facility> page1 = new SliceImpl<>(List.of(p1), PageRequest.of(1, 1), false);
        when(facilityRepository.findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(any(), any()))
                .thenReturn(page0, page1);
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(facilityRepository, times(2)).findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(any(), any());
        verify(notificationService, times(2))
                .notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("기존 알림 배치 조회가 실패하면 그 페이지는 스킵하고 발행하지 않는다")
    void 배치조회실패_페이지스킵() {
        stubDuePage(List.of(dueFacility(1L, COMPANY, "시설A")));
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any()))
                .thenThrow(new RuntimeException("DB 오류"));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    // ── 알림설정 게이팅(#540 ③) ──────────────────────────────────────────────

    @Test
    @DisplayName("설정 행이 없으면 기본값(사전알림 7일전)으로 사전알림 창 안이면 발행한다")
    void 사전알림_설정없음_기본값7일전_창안이면_발행() {
        // 도래일 = 오늘+5일 → 오늘+5 - 7일 = 오늘-2 <= 오늘 → 기본 7일 창 안.
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY.plusDays(5));
        stubDuePage(List.of(f));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"DUE\"");
    }

    @Test
    @DisplayName("기본값(7일전) 창 밖이면 발행하지 않는다")
    void 사전알림_설정없음_기본값7일전_창밖이면_발행안함() {
        // 도래일 = 오늘+10일 → 오늘+10 - 7일 = 오늘+3 > 오늘 → 아직 창 밖.
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY.plusDays(10));
        stubDuePage(List.of(f));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("사전알림을 꺼둔 설정이면 당일이어도 발행하지 않는다")
    void 사전알림_비활성화설정_당일이어도_발행안함() {
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY);
        stubDuePage(List.of(f));
        stubNoExistingNotifications();
        stubSettings(settingRow(OWNER, 1L, false, 7, false));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("사용자별 커스텀 사전알림 일수(14일전)가 기본값 대신 적용된다")
    void 사전알림_커스텀일수_적용() {
        // 도래일 = 오늘+10일 → 기본 7일 창은 밖이지만, 커스텀 14일 설정이면 오늘+10-14 = 오늘-4 <= 오늘 → 창 안.
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY.plusDays(10));
        stubDuePage(List.of(f));
        stubNoExistingNotifications();
        stubSettings(settingRow(OWNER, 1L, true, 14, false));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("경과알림 설정이 기본값(false)이면 overdue 시설물은 발행하지 않는다")
    void 경과알림_기본값비활성_발행안함() {
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubNoExistingNotifications();

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("경과알림 설정을 켜면 overdue 시설물에 OVERDUE 알림을 발행한다")
    void 경과알림_설정켜짐_발행() {
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubNoExistingNotifications();
        stubSettings(settingRow(OWNER, 1L, true, 7, true));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"OVERDUE\"");
    }

    @Test
    @DisplayName("같은 시설물·같은 도래일이라도 DUE와 OVERDUE는 서로 다른 알림으로 취급돼 dedup이 섞이지 않는다")
    void DUE와OVERDUE_dedup키가달라_서로독립적으로판정된다() {
        // 오늘 마감(DUE 창 안)이면서, 이미 같은 도래일로 OVERDUE 알림만 발행된 적이 있는 상황을 재현 —
        // 종류가 다르므로 DUE는 여전히 새로 발행돼야 한다(dedup 키에 kind가 없다면 잘못 스킵될 시나리오).
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY);
        stubDuePage(List.of(f));
        Notification existingOverdue = existingNotificationFor(f, Kind.OVERDUE);
        when(notificationRepository.findAllByUserIdInAndType(anySet(), any()))
                .thenReturn(List.of(existingOverdue));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"DUE\"");
    }

    @Test
    @DisplayName("알림설정 배치 조회가 실패해도 기본값으로 게이팅해 배치를 계속한다")
    void 알림설정조회실패_기본값으로계속() {
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY);
        stubDuePage(List.of(f));
        stubNoExistingNotifications();
        when(notificationSettingRepository.findAllByUserIdInAndFacilityIdIn(any(), any()))
                .thenThrow(new RuntimeException("설정 조회 실패"));

        scheduler.notifyFacilitiesDueToday();

        // 설정 조회가 실패해도 기본값(사전알림 활성/7일전)이 적용돼 당일 마감 시설물은 정상 발행된다.
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }
}