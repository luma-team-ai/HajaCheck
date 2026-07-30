package com.hajacheck.membership.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.ScheduledPlanChangeProperties;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.service.PlanExpiryWriter;
import com.hajacheck.membership.service.ScheduledPlanChangeFailure;
import com.hajacheck.membership.service.ScheduledPlanChangeResult;
import com.hajacheck.membership.service.ScheduledPlanChangeWriter;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * ScheduledPlanChangeScheduler 단위 테스트(#1105 / HAJA-526). {@code PlanExpirySchedulerTest} 와 같이
 * 고정 Clock 을 수동 주입하고 협력자는 Mockito mock 을 직접 생성자 주입한다(@InjectMocks 미사용).
 *
 * <p>여기서 고정하는 계약은 <b>스케줄러의 통제 로직</b>이다 — 비상 스위치·1회 상한·keyset 순회·건별 장애
 * 격리와 <b>실패 분류</b>(재시도로 풀리는 경합은 PENDING 유지, 도메인 위반은 FAILED 종료)·알림 발행.
 * 실제 전이(예약 적용·좌석 정지·결제 주기·멱등성)는 실 PostgreSQL 이 필요하므로
 * {@code ScheduledPlanChangeIntegrationTest} 가 담당한다.
 */
class ScheduledPlanChangeSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-07-27T15:30Z = 2026-07-28T00:30 KST — KST 00:00~09:00 구간(UTC 로는 전날)이라
    // 존을 잘못 잡으면 판정이 하루 어긋나는 구간이다.
    private static final Instant NOW = Instant.parse("2026-07-27T15:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, KST);

    private ScheduledPlanChangeRepository repository;
    private ScheduledPlanChangeWriter writer;
    // 2단계(#1177 미결제 유예 강등) 협력자 — 이 테스트는 1단계(예약 적용)의 통제 로직만 검증하므로
    // 기본 스텁(대상 0건)으로 두어 2단계가 아무것도 하지 않게 한다.
    private UserPlanRepository userPlanRepository;
    private PlanExpiryWriter planExpiryWriter;
    private NotificationService notificationService;
    private ScheduledPlanChangeProperties properties;
    private ScheduledPlanChangeScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduledPlanChangeRepository.class);
        writer = mock(ScheduledPlanChangeWriter.class);
        userPlanRepository = mock(UserPlanRepository.class);
        planExpiryWriter = mock(PlanExpiryWriter.class);
        notificationService = mock(NotificationService.class);
        properties = new ScheduledPlanChangeProperties();
        when(userPlanRepository.countPaymentGraceExpired(any(), any())).thenReturn(0L);
        scheduler = new ScheduledPlanChangeScheduler(
                repository, writer, userPlanRepository, planExpiryWriter,
                notificationService, properties, FIXED);
    }

    private ScheduledPlanChangeResult applied(Long recipientUserId) {
        return ScheduledPlanChangeResult.applied(recipientUserId, PlanName.STANDARD,
                PlanName.FREE, 900L, null, NOW.minusSeconds(60), List.of(7L, 8L), null);
    }

    private void stubTargets(long totalCount, List<Long> firstPage) {
        when(repository.countDue(any())).thenReturn(totalCount);
        when(repository.findDueIds(any(), eq(0L), any())).thenReturn(firstPage);
    }

    private ListAppender<ILoggingEvent> runCapturingLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduledPlanChangeScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.applyDueScheduledChanges();
        } finally {
            logger.detachAppender(appender);
        }
        return appender;
    }

    private boolean loggedAt(ListAppender<ILoggingEvent> appender, Level level, String fragment) {
        return appender.list.stream()
                .anyMatch(event -> event.getLevel() == level
                        && event.getFormattedMessage().contains(fragment));
    }

    @Test
    @DisplayName("Properties 기본값 — 켜짐 + 상한 100 + 잠금 대기 3초")
    void 기본값_고정() {
        ScheduledPlanChangeProperties defaults = new ScheduledPlanChangeProperties();

        assertThat(defaults.isEnabled())
                .as("꺼 두면 '신청은 받아 놓고 영원히 적용되지 않는' 상태가 된다 — 만료 강등 배치와 달리 "
                        + "대상이 사람이 만든 예약뿐이라 소급 대량 실행 표면이 없다")
                .isTrue();
        assertThat(defaults.getMaxPerRun()).isEqualTo(100);
        assertThat(defaults.getLockTimeoutMs())
                .as("잠금 대기 상한이 0(무제한)이면 한 건의 대기가 다른 배치까지 멈춘다")
                .isEqualTo(3000);
    }

    @Test
    @DisplayName("enabled=false면 대상 조회조차 하지 않고 아무것도 적용하지 않는다")
    void 비상스위치_꺼져있으면_완전_noop() {
        properties.setEnabled(false);

        scheduler.applyDueScheduledChanges();

        verifyNoInteractions(repository);
        verifyNoInteractions(writer);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("대상이 0건이면 writer 를 부르지 않는다")
    void 대상이_없으면_아무것도_하지_않는다() {
        when(repository.countDue(any())).thenReturn(0L);

        scheduler.applyDueScheduledChanges();

        verifyNoInteractions(writer);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("대상이 1회 상한을 넘으면 한 건도 적용하지 않고 ERROR 로 중단한다(부분 적용 금지)")
    void 상한초과면_아무것도_적용하지_않는다() {
        properties.setMaxPerRun(2);
        when(repository.countDue(any())).thenReturn(3L);

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verifyNoInteractions(writer);
        verifyNoInteractions(notificationService);
        assertThat(loggedAt(appender, Level.ERROR, "1회 상한"))
                .as("상한을 넘는 대량 적용은 정상 운영이 아니라 사고 신호다 — 조용히 일부만 처리하면 안 된다")
                .isTrue();
    }

    @Test
    @DisplayName("적용된 예약마다 PLAN_DOWNGRADED 알림을 1건 발행한다")
    void 적용시_알림_1건() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW))).thenReturn(applied(42L));

        scheduler.applyDueScheduledChanges();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(42L), eq(NotificationType.PLAN_DOWNGRADED), payload.capture());
        assertThat(payload.getValue())
                .as("사용자가 무엇이 어떻게 바뀌었는지 알 수 있어야 한다(정지 좌석 수 포함)")
                .contains("STANDARD").contains("FREE").contains("\"suspendedSeatCount\":2");
        assertThat(payload.getValue())
                .as("알림 payload 에 개인정보(구성원 id 목록 등)를 싣지 않는다")
                .doesNotContain("suspendedUserIds");
    }

    @Test
    @DisplayName("무효 취소(CANCELED)된 예약은 알림을 발행하지 않는다 — 사용자에게는 아무 일도 일어나지 않았다")
    void 무효취소는_알림을_발행하지_않는다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW)))
                .thenReturn(ScheduledPlanChangeResult.canceled("예약 생성 이후 구독이 전이됨(status=EXPIRED)"));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verifyNoInteractions(notificationService);
        assertThat(loggedAt(appender, Level.INFO, "무효 취소")).isTrue();
        assertThat(loggedAt(appender, Level.WARN, "실패"))
                .as("무효 취소는 실패가 아니다 — 실패로 세면 회차 요약 WARN 이 일상적으로 켜져 진짜 신호가 희석된다")
                .isFalse();
    }

    @Test
    @DisplayName("도메인 위반은 예약을 FAILED 로 종료시키고 신청자에게 실패 알림을 1건 보낸다")
    void 도메인위반은_FAILED로_종료되고_알림이_나간다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW)))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_PROTECTED_ACCOUNT));
        when(writer.markFailed(eq(11L), anyString()))
                .thenReturn(new ScheduledPlanChangeFailure(true, 42L, PlanName.FREE));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verify(writer).markFailed(eq(11L), eq("errorCode=ADMIN_PROTECTED_ACCOUNT"));
        assertThat(loggedAt(appender, Level.WARN, "ADMIN_PROTECTED_ACCOUNT"))
                .as("ErrorCode 를 버리면 운영자가 로그만 보고 원인을 특정할 수 없다")
                .isTrue();

        // FAILED 는 종료 상태라 재시도가 없고 조회는 PENDING 만 노출한다 — 알리지 않으면 신청자에게는
        // "예약을 걸었는데 어느 날 조용히 사라지고 요금제는 그대로"가 된다(리뷰 P2-5).
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(42L), eq(NotificationType.PLAN_DOWNGRADE_FAILED),
                payload.capture());
        assertThat(payload.getValue()).contains("FREE").contains("ADMIN_PROTECTED_ACCOUNT");
    }

    @Test
    @DisplayName("이번 실행이 종료시킨 게 아니면(이미 다른 상태) 실패 알림을 보내지 않는다 — 중복 통지 방지")
    void 이미_종료된_예약은_실패알림을_보내지_않는다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW)))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_PROTECTED_ACCOUNT));
        when(writer.markFailed(eq(11L), anyString())).thenReturn(ScheduledPlanChangeFailure.notMarked());

        scheduler.applyDueScheduledChanges();

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("확인 범위를 넘는 정지 규모는 도메인 위반으로 취급돼 FAILED 로 종료된다(적용 알림은 나가지 않는다)")
    void 확인범위_초과는_FAILED로_종료된다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW)))
                .thenThrow(new BusinessException(ErrorCode.PLAN_SCHEDULE_CONFIRMED_OVERFLOW_EXCEEDED));
        when(writer.markFailed(eq(11L), anyString()))
                .thenReturn(new ScheduledPlanChangeFailure(true, 42L, PlanName.FREE));

        scheduler.applyDueScheduledChanges();

        verify(writer).markFailed(eq(11L), eq("errorCode=PLAN_SCHEDULE_CONFIRMED_OVERFLOW_EXCEEDED"));
        verify(notificationService).notify(eq(42L), eq(NotificationType.PLAN_DOWNGRADE_FAILED), anyString());
        // 적용 알림은 절대 나가면 안 된다 — 아무것도 적용되지 않았다.
        verify(notificationService, never())
                .notify(anyLong(), eq(NotificationType.PLAN_DOWNGRADED), anyString());
    }

    @Test
    @DisplayName("활성 구독 경합(PLAN_ACTIVE_SUBSCRIPTION_CONFLICT)은 PENDING 으로 남겨 다음 회차에 재시도한다")
    void 활성구독_경합은_PENDING을_유지한다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW)))
                .thenThrow(new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verify(writer, never()).markFailed(anyLong(), anyString());
        assertThat(loggedAt(appender, Level.WARN, "실패 1건"))
                .as("경합은 결함이 아니다 — 실패로 세면 회차 요약 WARN 이 일상적으로 켜진다")
                .isFalse();
    }

    @Test
    @DisplayName("행 잠금 대기 초과도 경합으로 보고 PENDING 을 유지한다")
    void 잠금경합은_PENDING을_유지한다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW)))
                .thenThrow(new CannotAcquireLockException("lock timeout"));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verify(writer, never()).markFailed(anyLong(), anyString());
        assertThat(loggedAt(appender, Level.INFO, "행 잠금 대기 초과")).isTrue();
    }

    @Test
    @DisplayName("예상 밖 예외는 실패로 세되 PENDING 을 유지한다 — 일시 장애로 신청한 하향을 영구히 잃지 않는다")
    void 예상밖_예외는_실패로_세되_상태를_바꾸지_않는다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW)))
                .thenThrow(new IllegalStateException("일시 장애"));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verify(writer, never()).markFailed(anyLong(), anyString());
        assertThat(loggedAt(appender, Level.WARN, "실패 1건"))
                .as("매시 같은 건이 실패하는 상황은 요약 WARN 으로 관측 가능해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("예상 밖 무결성 위반은 예외 메시지 원문을 로그에 싣지 않는다(위반 컬럼 값 유출 방지)")
    void 무결성위반_로그에_값을_남기지_않는다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW))).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"x\" Detail: Key (email)=(leak@haja.test)"));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verify(writer, never()).markFailed(anyLong(), anyString());
        assertThat(appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .anyMatch(event -> event.getFormattedMessage().contains("leak@haja.test")))
                .as("PostgreSQL 무결성 위반 메시지는 위반 컬럼의 실제 값을 담는다 — 로그 평문 유출 금지")
                .isFalse();
    }

    @Test
    @DisplayName("한 건의 실패가 같은 회차의 나머지 예약 처리를 막지 않는다")
    void 건별_장애가_격리된다() {
        stubTargets(3L, List.of(11L, 12L, 13L));
        when(writer.applyDueChange(eq(11L), eq(NOW))).thenThrow(new IllegalStateException("장애"));
        when(writer.applyDueChange(eq(12L), eq(NOW))).thenReturn(applied(42L));
        when(writer.applyDueChange(eq(13L), eq(NOW))).thenReturn(applied(43L));

        scheduler.applyDueScheduledChanges();

        verify(writer).applyDueChange(eq(12L), eq(NOW));
        verify(writer).applyDueChange(eq(13L), eq(NOW));
        verify(notificationService).notify(eq(42L), eq(NotificationType.PLAN_DOWNGRADED), anyString());
        verify(notificationService).notify(eq(43L), eq(NotificationType.PLAN_DOWNGRADED), anyString());
    }

    @Test
    @DisplayName("keyset 페이징으로 다음 페이지를 이어서 순회한다(offset 페이징이면 대상이 통째로 건너뛰어진다)")
    void keyset_순회() {
        List<Long> firstPage = LongStream.rangeClosed(1, 50).boxed().toList();
        when(repository.countDue(any())).thenReturn(51L);
        when(repository.findDueIds(any(), eq(0L), any())).thenReturn(firstPage);
        when(repository.findDueIds(any(), eq(50L), any())).thenReturn(List.of(51L));
        when(writer.applyDueChange(anyLong(), eq(NOW))).thenReturn(applied(42L));

        scheduler.applyDueScheduledChanges();

        // 마지막 id 를 커서로 다음 페이지를 읽었는지 — 여기가 offset 이면 51번은 영영 처리되지 않는다.
        verify(repository).findDueIds(any(), eq(50L), any());
        verify(writer).applyDueChange(eq(51L), eq(NOW));
    }

    @Test
    @DisplayName("알림 발행이 실패해도 이미 커밋된 적용을 되돌리지 않고 WARN 으로만 표면화한다")
    void 알림실패는_적용을_되돌리지_않는다() {
        stubTargets(1L, List.of(11L));
        when(writer.applyDueChange(eq(11L), eq(NOW))).thenReturn(applied(42L));
        org.mockito.Mockito.doThrow(new IllegalStateException("알림 저장 실패"))
                .when(notificationService).notify(anyLong(), any(), anyString());

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        assertThat(loggedAt(appender, Level.WARN, "알림 발행 실패")).isTrue();
        // 적용 자체는 성공으로 집계된다(권한은 내려갔는데 알림이 없는 상태가 그 반대보다 낫다).
        assertThat(loggedAt(appender, Level.INFO, "적용 1건")).isTrue();
    }
}
