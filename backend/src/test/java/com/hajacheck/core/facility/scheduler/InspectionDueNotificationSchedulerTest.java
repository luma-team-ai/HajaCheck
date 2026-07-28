package com.hajacheck.core.facility.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/**
 * InspectionDueNotificationScheduler 단위 테스트(NOTI-01, #425 / 알림설정 게이팅, #540 ③ / DB 유니크
 * 제약 기반 멱등성 전환, #1050). BuiltYearValidatorTest 와 같이 고정 Clock 을 수동 주입하고, 협력자는
 * Mockito mock 을 직접 생성자 주입한다(@InjectMocks 미사용).
 *
 * <p>#1050 이후 kind가 있는(신규) payload의 멱등성은 DB 유니크 인덱스가 전담한다 — 이 단위 테스트는
 * 실제 제약을 검증할 수 없으므로(Mockito는 DB가 아님), "notify()가 unique violation을 던지면 스케줄러가
 * 그것을 skip으로 처리하고 예외를 전파하지 않는지"만 검증한다. 실제 제약 자체(같은 키 재삽입 거부,
 * kind가 다르면 통과, kind가 NULL인 레거시 행은 못 잡음)는 Testcontainers 기반
 * V24InspectionDueNotificationDedupeUniqueIndexTest가 증명한다. kind 없는 레거시 payload의 애플리케이션
 * 레벨 dedupe(그 사각지대 방어)는 이 클래스에서 여전히 목으로 검증한다.
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
        // 대부분의 테스트는 kind 없는 레거시 이력도 없는(=DB 유니크 인덱스에만 맡기는) 시나리오다.
        lenient().when(notificationRepository.findLegacyKindLessInspectionDueByUserIdIn(any()))
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

    private void stubSettings(InspectionNotificationSetting... settings) {
        when(notificationSettingRepository.findAllByUserIdInAndFacilityIdIn(any(), any()))
                .thenReturn(List.of(settings));
    }

    private static DataIntegrityViolationException uniqueViolation() {
        return new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_notifications_inspection_due_dedupe\"");
    }

    @Test
    @DisplayName("오늘 마감 시설물에 INSPECTION_DUE 알림을 발행한다")
    void 오늘마감시설_알림발행() {
        stubDuePage(List.of(dueFacility(1L, COMPANY, "시설A")));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("DB 유니크 인덱스가 unique violation을 던지면 이미 발행된 것으로 간주해 조용히 skip한다(#1050)")
    void notify가unique_violation을던지면_예외전파없이skip한다() {
        stubDuePage(List.of(dueFacility(1L, COMPANY, "시설A")));
        doThrow(uniqueViolation()).when(notificationService).notify(anyLong(), any(), anyString());

        // 예외가 스케줄러 밖으로 전파되지 않아야 한다 — 배치가 계속 정상 종료된다.
        scheduler.notifyFacilitiesDueToday();

        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("한 시설물의 notify가 예외를 던져도 같은 owner의 다음 시설물은 계속 처리한다")
    void notify예외_격리_다음시설계속() {
        stubDuePage(List.of(dueFacility(1L, COMPANY, "시설1"), dueFacility(2L, COMPANY, "시설2")));
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
    @DisplayName("경과알림 설정이 켜진 overdue 시설물은 재실행 시 DB 유니크 인덱스가 막아 재발행되지 않는다"
            + "(스팸 방지, #1050 — 조회 기반 dedupe 대신 unique violation 발생 시 skip으로 처리)")
    void overdue_경과알림설정켜짐_재실행시_unique_violation으로_스킵() {
        // overdue(어제 마감) 시설물 — 도래일 값은 재스케줄 전까지 바뀌지 않는다. warnOnOverdueEnabled=true로
        // 명시 설정해야 #540 ③ 게이팅상 OVERDUE 알림이 발행된다(기본값은 false — 별도 테스트로 커버).
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 7, true));

        // 1회차: 정상 발행
        scheduler.notifyFacilitiesDueToday();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"OVERDUE\"");

        // 2회차(재실행): 실제 운영에서는 V24 유니크 인덱스가 같은 (facilityId, dueAt, kind) 재삽입을
        // unique violation으로 거부한다 — 여기서는 그 결과를 시뮬레이션한다.
        doThrow(uniqueViolation()).when(notificationService).notify(anyLong(), any(), anyString());
        scheduler.notifyFacilitiesDueToday();

        // 총 notify 시도는 2회(1회 성공 + 1회 unique violation으로 skip) — 예외 없이 배치가 정상 종료된다.
        verify(notificationService, times(2)).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("owner가 3명이어도 레거시 알림 조회는 정확히 1회만 호출된다(N+1 방지)")
    void owner3명_레거시알림조회_1회만() {
        stubDuePage(List.of(
                dueFacility(1L, 100L, "A시설"),
                dueFacility(2L, 200L, "B시설"),
                dueFacility(3L, 300L, "C시설")));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationRepository, times(1))
                .findLegacyKindLessInspectionDueByUserIdIn(anySet());
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

        scheduler.notifyFacilitiesDueToday();

        verify(facilityRepository, times(2)).findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(any(), any());
        verify(notificationService, times(2))
                .notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("레거시 알림 조회가 실패하면 그 페이지는 스킵하고 발행하지 않는다")
    void 레거시조회실패_페이지스킵() {
        stubDuePage(List.of(dueFacility(1L, COMPANY, "시설A")));
        when(notificationRepository.findLegacyKindLessInspectionDueByUserIdIn(any()))
                .thenThrow(new RuntimeException("DB 오류"));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    // ── 알림설정 게이팅(#540 ③) ──────────────────────────────────────────────

    @Test
    @DisplayName("설정 행이 없으면 기본값(사전알림 7일전)으로 사전알림 창 안이면 BEFORE 알림을 발행한다"
            + "(사람검수 P2 #1032 — BEFORE/DUE 3분리 전에는 이 창 전체가 DUE 하나로 묶여 있었다)")
    void 사전알림_설정없음_기본값7일전_창안이면_BEFORE발행() {
        // 도래일 = 오늘+5일 → 오늘+5 - 7일 = 오늘-2 <= 오늘 → 기본 7일 창 안, 아직 당일은 아니므로 BEFORE.
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY.plusDays(5));
        stubDuePage(List.of(f));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"BEFORE\"");
    }

    @Test
    @DisplayName("기본값(7일전) 창 밖이면 발행하지 않는다")
    void 사전알림_설정없음_기본값7일전_창밖이면_발행안함() {
        // 도래일 = 오늘+10일 → 오늘+10 - 7일 = 오늘+3 > 오늘 → 아직 창 밖.
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY.plusDays(10));
        stubDuePage(List.of(f));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("notifyBeforeEnabled=false여도 dueAt==오늘(당일)이면 DUE는 발행된다"
            + "(사람검수 P2 #1032 최우선 회귀 수정 — main의 기존 배포 동작인 '당일 무조건 발행'을 복원)")
    void 당일_notifyBeforeEnabled꺼져있어도_DUE는발행된다() {
        // #540 ③ 최초 구현은 사전창 전체(BEFORE~DUE)를 notifyBeforeEnabled 하나로 게이팅해, 이 설정을
        // 끄면 당일 알림까지 함께 사라지는 회귀가 있었다. DUE(dueAt==오늘)는 이 설정과 무관하게 항상
        // 발행돼야 한다.
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY);
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, false, 7, false));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"DUE\"");
    }

    @Test
    @DisplayName("사용자별 커스텀 사전알림 일수(14일전)가 기본값 대신 적용된다")
    void 사전알림_커스텀일수_적용() {
        // 도래일 = 오늘+10일 → 기본 7일 창은 밖이지만, 커스텀 14일 설정이면 오늘+10-14 = 오늘-4 <= 오늘 → 창 안.
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY.plusDays(10));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 14, false));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }

    @Test
    @DisplayName("경과알림 설정 행이 없으면 기본값(true, HAJA-498/V21)으로 overdue 시설물에 발행한다")
    void 경과알림_설정없음_기본값true로_발행() {
        // #540 ③ 최초 도입 시 이 기본값이 false였다가, 연체 시설물에 알림이 더 이상 발행되지 않는 회귀가
        // 발견돼 Polalise 승인(옵션1, HAJA-498/V21)으로 true로 되돌렸다 — 이 테스트가 그 회귀 고정이다.
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"OVERDUE\"");
    }

    @Test
    @DisplayName("경과알림 설정을 명시적으로 켜도(기본값과 동일) overdue 시설물에 OVERDUE 알림을 발행한다")
    void 경과알림_설정명시적으로켜짐_발행() {
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 7, true));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"OVERDUE\"");
    }

    @Test
    @DisplayName("경과알림 설정을 명시적으로 끄면(기본값 true와 반대) overdue 시설물에 발행하지 않는다")
    void 경과알림_설정명시적으로꺼짐_발행안함() {
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 7, false));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("kind 필드 없는 구(舊) payload 이력이 있으면 overdue 시설물의 OVERDUE 알림이 재발행되지 않는다"
            + "(#1050 — V24 유니크 인덱스는 kind가 NULL인 레거시 행을 못 잡으므로 앱 레벨 레거시 체크가 여전히 필요)")
    void 구payload이력있으면_overdue시설물OVERDUE재발행안됨() {
        // #540 이전 저장분은 {facilityId, facilityName, nextInspectionDueAt}만 담고 kind 필드가 없다.
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 7, true));
        String legacyPayload = "{\"facilityId\":1,\"facilityName\":\"연체시설\",\"nextInspectionDueAt\":\""
                + TODAY.minusDays(1) + "\"}";
        Notification legacyNotification =
                Notification.create(OWNER, NotificationType.INSPECTION_DUE, legacyPayload);
        when(notificationRepository.findLegacyKindLessInspectionDueByUserIdIn(any()))
                .thenReturn(List.of(legacyNotification));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("kind 필드 없는 구(舊) payload 이력이 있어도 dueAt이 다르면 새로 발행된다"
            + "(레거시 체크가 dueAt까지 정확히 매칭하는지 — facilityId만 비교하는 회귀가 없는지 고정)")
    void 구payload이력있어도_dueAt다르면_새로발행된다() {
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 7, true));
        // 같은 facilityId=1 이지만 도래일이 다른(예전 회차) 레거시 이력 — 이번 회차와는 매칭되면 안 된다.
        String legacyPayload = "{\"facilityId\":1,\"facilityName\":\"연체시설\",\"nextInspectionDueAt\":\""
                + TODAY.minusDays(30) + "\"}";
        Notification legacyNotification =
                Notification.create(OWNER, NotificationType.INSPECTION_DUE, legacyPayload);
        when(notificationRepository.findLegacyKindLessInspectionDueByUserIdIn(any()))
                .thenReturn(List.of(legacyNotification));

        scheduler.notifyFacilitiesDueToday();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"kind\":\"OVERDUE\"");
    }

    @Test
    @DisplayName("설정 저장 주체와 무관하게(소유자 키로 저장됐다면) warnOnOverdueEnabled=false면 overdue 알림이 억제된다"
            + "(PR머신 P2 #1032 — InspectionNotificationSettingService가 요청자가 아닌 소유자 키로 저장하도록 수정)")
    void 설정저장주체와무관하게_소유자키로저장된값이_스케줄러게이팅에그대로반영된다() {
        // 스케줄러는 항상 settingKey(회사 소유자 ID, facilityId)로만 설정을 조회한다 — 이 행이 실제로
        // "소유자가 아닌 구성원이 저장한 것"이었는지는 스케줄러 입장에서 알 수도, 신경 쓸 필요도 없다.
        // InspectionNotificationSettingService가 항상 소유자 ID로 저장하도록 고쳐졌으므로(P2-1),
        // 여기서는 "그 키로 저장된 값이 그대로 게이팅에 반영되는지"만 확인한다(서비스 쪽 검증은
        // InspectionNotificationSettingServiceTest 참고).
        Facility f = dueFacility(1L, COMPANY, "연체시설", TODAY.minusDays(1));
        stubDuePage(List.of(f));
        stubSettings(settingRow(OWNER, 1L, true, 7, false));

        scheduler.notifyFacilitiesDueToday();

        verify(notificationService, never()).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("알림설정 배치 조회가 실패해도 기본값으로 게이팅해 배치를 계속한다")
    void 알림설정조회실패_기본값으로계속() {
        Facility f = dueFacility(1L, COMPANY, "시설A", TODAY);
        stubDuePage(List.of(f));
        when(notificationSettingRepository.findAllByUserIdInAndFacilityIdIn(any(), any()))
                .thenThrow(new RuntimeException("설정 조회 실패"));

        scheduler.notifyFacilitiesDueToday();

        // 설정 조회가 실패해도 기본값(사전알림 활성/7일전)이 적용돼 당일 마감 시설물은 정상 발행된다.
        verify(notificationService).notify(eq(OWNER), eq(NotificationType.INSPECTION_DUE), anyString());
    }
}
