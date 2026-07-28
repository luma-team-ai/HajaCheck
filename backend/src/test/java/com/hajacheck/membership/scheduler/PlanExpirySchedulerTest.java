package com.hajacheck.membership.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.membership.config.PlanExpiryProperties;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.service.PlanExpiryResult;
import com.hajacheck.membership.service.PlanExpiryWriter;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

/**
 * PlanExpiryScheduler 단위 테스트(#1145 / HAJA-549). {@code InspectionDueNotificationSchedulerTest}·
 * {@code PendingBusinessReverifySchedulerTest} 와 같이 고정 Clock 을 수동 주입하고, 협력자는 Mockito
 * mock 을 직접 생성자 주입한다(@InjectMocks 미사용).
 *
 * <p>여기서 고정하는 계약은 <b>스케줄러의 통제 로직</b>이다 — 기동 스위치·1회 상한·건별 장애 격리·
 * 알림 발행·keyset 순회. 실제 전이(만료→FREE 발급·사용량 이월·좌석 정지)와 멱등성은 실 PostgreSQL 이
 * 필요하므로 {@code PlanExpiryIntegrationTest} 가 담당한다.
 */
class PlanExpirySchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-07-27T15:30Z = 2026-07-28T00:30 KST — KST 00:00~09:00 구간(UTC 로는 전날)이라
    // 존을 잘못 잡으면 판정이 하루 어긋나는 구간이다(#1104 백필 주석의 그 함정).
    private static final Instant NOW = Instant.parse("2026-07-27T15:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, KST);

    private UserPlanRepository userPlanRepository;
    private PlanExpiryWriter planExpiryWriter;
    private NotificationService notificationService;
    private PlanExpiryProperties properties;
    private PlanExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        userPlanRepository = mock(UserPlanRepository.class);
        planExpiryWriter = mock(PlanExpiryWriter.class);
        notificationService = mock(NotificationService.class);
        properties = new PlanExpiryProperties();
        // 운영 기본값은 비활성이다 — 대부분의 테스트는 "켠 상태"의 동작을 보므로 여기서 켠다.
        properties.setEnabled(true);
        scheduler = new PlanExpiryScheduler(
                userPlanRepository, planExpiryWriter, notificationService, properties, FIXED);
    }

    private PlanExpiryResult downgraded(Long recipientUserId) {
        return PlanExpiryResult.downgraded(
                recipientUserId, PlanName.STANDARD, 900L, null, NOW.minusSeconds(3600), 2);
    }

    private void stubTargets(long totalCount, List<Long> firstPage) {
        when(userPlanRepository.countExpiryTargets(eq(UserPlanStatus.ACTIVE), any()))
                .thenReturn(totalCount);
        when(userPlanRepository.findExpiryTargetIds(eq(UserPlanStatus.ACTIVE), any(), eq(0L), any()))
                .thenReturn(firstPage);
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(PlanExpiryScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(PlanExpiryScheduler.class)).detachAppender(appender);
    }

    private boolean loggedAt(ListAppender<ILoggingEvent> appender, Level level, String fragment) {
        return appender.list.stream()
                .anyMatch(event -> event.getLevel() == level
                        && event.getFormattedMessage().contains(fragment));
    }

    @Test
    @DisplayName("enabled=false면 대상 조회조차 하지 않고 아무것도 강등하지 않는다(기본값 = 소급 대량 강등 방지)")
    void 기동스위치_꺼져있으면_완전_noop() {
        properties.setEnabled(false);

        scheduler.expireOverduePlans();

        // "조회는 하되 바꾸지 않는다"가 아니라 조회 자체를 하지 않아야 한다(#1145 §4).
        verifyNoInteractions(userPlanRepository);
        verifyNoInteractions(planExpiryWriter);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("PlanExpiryProperties 기본값은 비활성 + 유예 0일 + 1회 상한 50이다")
    void 기본값_고정() {
        PlanExpiryProperties defaults = new PlanExpiryProperties();

        assertThat(defaults.isEnabled())
                .as("기본 활성이면 프리플라이트 전에 기존 유료 회사가 일괄 강등된다(#1145 §2-1)")
                .isFalse();
        assertThat(defaults.getGracePeriod()).isEqualTo(Duration.ZERO);
        assertThat(defaults.getMaxPerRun()).isEqualTo(50);
    }

    @Test
    @DisplayName("대상이 1회 상한을 넘으면 단 한 건도 강등하지 않고 ERROR 로그 후 중단한다(부분 강등 금지)")
    void 상한초과시_0건처리_ERROR로그() {
        properties.setMaxPerRun(50);
        when(userPlanRepository.countExpiryTargets(eq(UserPlanStatus.ACTIVE), any())).thenReturn(51L);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            scheduler.expireOverduePlans();
        } finally {
            detachAppender(appender);
        }

        // 대상 목록 조회조차 하지 않는다 — 부분 강등이 남을 여지를 원천 차단.
        verify(userPlanRepository, never()).findExpiryTargetIds(any(), any(), anyLong(), any());
        verifyNoInteractions(planExpiryWriter);
        verifyNoInteractions(notificationService);
        assertThat(loggedAt(appender, Level.ERROR, "1회 상한"))
                .as("상한 초과는 정상 운영이 아니라 사고 신호라 ERROR로 표면화해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("대상 건수가 상한과 같으면(경계) 정상 처리한다")
    void 상한과_같으면_처리한다() {
        properties.setMaxPerRun(2);
        stubTargets(2L, List.of(11L, 12L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        verify(planExpiryWriter, times(2)).expireToFreePlan(anyLong(), any());
    }

    @Test
    @DisplayName("대상 0건이면 강등도 알림도 없다")
    void 대상없으면_아무일도_없다() {
        when(userPlanRepository.countExpiryTargets(eq(UserPlanStatus.ACTIVE), any())).thenReturn(0L);

        scheduler.expireOverduePlans();

        verify(userPlanRepository, never()).findExpiryTargetIds(any(), any(), anyLong(), any());
        verifyNoInteractions(planExpiryWriter);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("강등 1건마다 PLAN_EXPIRED 알림을 정확히 1건 발행한다")
    void 강등시_알림_1건_발행() {
        stubTargets(1L, List.of(42L));
        when(planExpiryWriter.expireToFreePlan(eq(42L), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        verify(notificationService, times(1))
                .notify(eq(7L), eq(NotificationType.PLAN_EXPIRED), anyString());
    }

    @Test
    @DisplayName("만료 판정 기준시각은 now - gracePeriod 이며 Instant 그대로 비교한다(KST 새벽 구간에서도 날짜로 잘리지 않는다)")
    void 기준시각은_now빼기유예_그대로다() {
        properties.setGracePeriod(Duration.ofDays(3));
        when(userPlanRepository.countExpiryTargets(eq(UserPlanStatus.ACTIVE), any())).thenReturn(0L);

        scheduler.expireOverduePlans();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(userPlanRepository).countExpiryTargets(eq(UserPlanStatus.ACTIVE), threshold.capture());
        // NOW 는 KST 2026-07-28T00:30(=UTC 전날 15:30) — 존을 잘못 타 날짜로 절삭하면 값이 달라진다.
        assertThat(threshold.getValue()).isEqualTo(NOW.minus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("유예 0일(기본)이면 기준시각이 곧 현재 시각이다 — 만료 즉시 강등")
    void 유예0일이면_기준시각은_현재시각() {
        when(userPlanRepository.countExpiryTargets(eq(UserPlanStatus.ACTIVE), any())).thenReturn(0L);

        scheduler.expireOverduePlans();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(userPlanRepository).countExpiryTargets(eq(UserPlanStatus.ACTIVE), threshold.capture());
        assertThat(threshold.getValue()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("writer가 스킵을 반환하면(다른 경로가 먼저 전이) 알림을 발행하지 않는다")
    void 스킵이면_알림없음() {
        stubTargets(1L, List.of(42L));
        when(planExpiryWriter.expireToFreePlan(eq(42L), any()))
                .thenReturn(PlanExpiryResult.skipped("결제 주기가 갱신됨"));

        scheduler.expireOverduePlans();

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("한 건의 실패가 나머지 건 처리를 막지 않는다(건별 독립 트랜잭션)")
    void 한건_실패해도_나머지_계속() {
        stubTargets(3L, List.of(1L, 2L, 3L));
        when(planExpiryWriter.expireToFreePlan(eq(1L), any())).thenReturn(downgraded(7L));
        when(planExpiryWriter.expireToFreePlan(eq(2L), any()))
                .thenThrow(new RuntimeException("전이 실패"));
        when(planExpiryWriter.expireToFreePlan(eq(3L), any())).thenReturn(downgraded(8L));

        assertThatCode(() -> scheduler.expireOverduePlans()).doesNotThrowAnyException();

        verify(planExpiryWriter).expireToFreePlan(eq(3L), any());
        verify(notificationService).notify(eq(7L), eq(NotificationType.PLAN_EXPIRED), anyString());
        verify(notificationService).notify(eq(8L), eq(NotificationType.PLAN_EXPIRED), anyString());
        // 실패한 2번 건은 강등되지 않았으므로 알림도 나가지 않는다(총 2건).
        verify(notificationService, times(2)).notify(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("부분 UQ 위반(활성 구독 경합)은 조용히 skip하고 예외를 전파하지 않는다")
    void 무결성위반은_스킵() {
        stubTargets(2L, List.of(1L, 2L));
        when(planExpiryWriter.expireToFreePlan(eq(1L), any()))
                .thenThrow(new DataIntegrityViolationException("uq_user_plans_active_company"));
        when(planExpiryWriter.expireToFreePlan(eq(2L), any())).thenReturn(downgraded(7L));

        assertThatCode(() -> scheduler.expireOverduePlans()).doesNotThrowAnyException();

        verify(notificationService, times(1))
                .notify(eq(7L), eq(NotificationType.PLAN_EXPIRED), anyString());
    }

    @Test
    @DisplayName("알림 발행이 실패해도 이미 커밋된 강등을 되돌리지 않고 배치는 계속된다")
    void 알림실패해도_배치는_계속() {
        stubTargets(2L, List.of(1L, 2L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));
        doThrow(new RuntimeException("알림 발행 실패")).doNothing()
                .when(notificationService).notify(anyLong(), any(), anyString());

        assertThatCode(() -> scheduler.expireOverduePlans()).doesNotThrowAnyException();

        verify(planExpiryWriter).expireToFreePlan(eq(2L), any());
    }

    @Test
    @DisplayName("페이지가 꽉 차면 마지막 id를 기준(keyset)으로 다음 페이지를 이어 조회한다")
    void keyset_페이징으로_다음페이지를_읽는다() {
        int pageSize = 50;
        List<Long> firstPage = LongStream.rangeClosed(1, pageSize).boxed().toList();
        properties.setMaxPerRun(200);
        when(userPlanRepository.countExpiryTargets(eq(UserPlanStatus.ACTIVE), any())).thenReturn(52L);
        when(userPlanRepository.findExpiryTargetIds(eq(UserPlanStatus.ACTIVE), any(), eq(0L), any()))
                .thenReturn(firstPage);
        when(userPlanRepository.findExpiryTargetIds(
                eq(UserPlanStatus.ACTIVE), any(), eq((long) pageSize), any()))
                .thenReturn(List.of(51L, 52L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        // offset 페이징이었다면 처리로 결과집합이 앞으로 당겨져 51·52가 통째로 누락됐을 자리다.
        verify(planExpiryWriter).expireToFreePlan(eq(51L), any());
        verify(planExpiryWriter).expireToFreePlan(eq(52L), any());
        verify(planExpiryWriter, times(52)).expireToFreePlan(anyLong(), any());
    }

    @Test
    @DisplayName("사전 건수 검사 이후 대상이 늘어나도 1회 상한을 넘겨 강등하지 않는다(2차 방어)")
    void 순회중에도_상한을_넘기지_않는다() {
        properties.setMaxPerRun(2);
        // count 는 상한 이내(2)로 통과시키고, 실제 목록은 그보다 많은 3건을 돌려준다
        // (사전 검사와 순회 사이에 새로 만료된 구독이 끼어든 상황).
        when(userPlanRepository.countExpiryTargets(eq(UserPlanStatus.ACTIVE), any())).thenReturn(2L);
        when(userPlanRepository.findExpiryTargetIds(eq(UserPlanStatus.ACTIVE), any(), eq(0L), any()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        verify(planExpiryWriter, times(2)).expireToFreePlan(anyLong(), any());
        verify(planExpiryWriter, never()).expireToFreePlan(eq(3L), any());
    }

    @Test
    @DisplayName("대상 조회는 항상 ACTIVE 상태만 본다(EXPIRED/UPGRADE_REQUESTED는 대상 아님)")
    void 대상은_ACTIVE만() {
        stubTargets(1L, List.of(1L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        ArgumentCaptor<UserPlanStatus> status = ArgumentCaptor.forClass(UserPlanStatus.class);
        verify(userPlanRepository).countExpiryTargets(status.capture(), any());
        assertThat(status.getValue()).isEqualTo(UserPlanStatus.ACTIVE);

        List<UserPlanStatus> listedStatuses = new ArrayList<>();
        ArgumentCaptor<UserPlanStatus> listStatus = ArgumentCaptor.forClass(UserPlanStatus.class);
        verify(userPlanRepository, times(1))
                .findExpiryTargetIds(listStatus.capture(), any(), anyLong(), any(Pageable.class));
        listedStatuses.addAll(listStatus.getAllValues());
        assertThat(listedStatuses).containsOnly(UserPlanStatus.ACTIVE);
    }
}
